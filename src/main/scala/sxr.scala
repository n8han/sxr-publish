package sxr

import sbt._
import dispatch._
import dispatch.Http._
import java.net.URI
import javax.crypto
import org.apache.commons.codec.binary.Base64.encodeBase64

trait Write extends BasicScalaProject {
  import Utils._
  /** Publishing target host, defaults to "sourced.implicit.ly" */
  def sxrHostname = "sourced.implicit.ly"
  /** Defaults to sxrHostname on port 80 */
  def sxrHost = :/(sxrHostname)
  /** Override to define a particular sxr artifact */
  def sxr_artifact = "org.scala-tools.sxr" %% "sxr" % "0.2.6"
  /** Custom config, to keep sxr's jar separate and hide the dependency when publishing */
  lazy val SxrPlugin = (new Configuration("sxr")) hide
  /** Artifact assigned to SxrPlugin configuration */
  lazy val sxr = sxr_artifact % SxrPlugin.name
  abstract override def excludeIDs = 
    if (sxrEnabled.value) super.excludeIDs
    else sxr :: super.excludeIDs.toList

  /** Output path of the compiler plugin, does not control the path but should reflect it */
  def sxrMainPath = (outputPath / "classes.sxr") ##
  /** Output path of the compiler plugin's test sources, not currently used */
  def sxrTestPath = (outputPath / "test-classes.sxr") ##

  /** Custom sxr configuration, so that sxr is not on the regular compile path */
  val sxrConf = Configurations.config("sxr")
  /** Select the jar in the sxr configuration path */
  def sxrFinder = configurationPath(sxrConf) * "*.jar"
  /** Normally inherited from MavenStyleScalaPaths */
  def mainScalaSourcePath: Path
  /** Returns sxr as a compiler option only if sxrEnabled is set to true */
  protected def sxrOptions = sxrFinder.get.filter { f => sxrEnabled.value } flatMap { p =>
    new CompileOption("-Xplugin:" + p.absolutePath) :: 
      new CompileOption("-P:sxr:link-file:" + sxrLinksPath.absolutePath) ::
      new CompileOption("-P:sxr:base-directory:" + mainScalaSourcePath) :: Nil
  } toList
  /** Regex extractor that pulls names and versions from jarfile names */
  private val DepJar = """^([^_]+)(?:_[^-]+)?-(.+)\.jar""".r
  /** Guessed list of dependencies from all jars under managedDependencyPath */
  private def jarIds = (Set.empty[(String,String)] /: (managedDependencyPath ** "*.jar").get) {
    (set, item) => item.name match {
      case DepJar(name, vers) => set + ((name, vers))
      case _ => set
    } 
  }
  /** Dependency ids from other projects in the same build */
  private def projectIds = (dependencies ++ (this :: Nil)) map { 
    case proj: Publish => (proj.normalizedName, proj.sxrVersion)
    case proj => (proj.normalizedName, proj.version.toString) 
  }
  /** Scala library dependency id */
  private def scalaId = ("scala-library", buildScalaVersion)
  /** Temporary file storing sxr links */
  def sxrLinksPath = outputPath / "sxr.links"
  /** Updated sxrLinksPath with latest from sxrHost filtered by all found dependency ids */
  def updateSxrLinks =
    http(gzip(sxrHost) / "sxr.links" >~ { source =>
      val deps = jarIds ++ projectIds + scalaId
      sbt.FileUtilities.write(sxrLinksPath.asFile, log) { writer =>
        source.getLines.filter { line => line.trim.split('/').reverse match {
          case Seq(vers, name, _*) => deps.contains((name, vers))
        } } foreach { line => writer.write(line) }
        None
      }
    })
  /** Adds in whatever is returned by sxrOption */
  abstract override def compileOptions = sxrOptions ++ super.compileOptions
  /** Variable used to enable the sxr plugin for the duration of tasks requiring it */
  private val sxrEnabled = new scala.util.DynamicVariable(false)

  lazy val writeSxr = writeSxrAction describedAs
    "Clean and test-compile with the sxr plugin enabled, writes annotated sources"
  def writeSxrAction =
    fileTask((sxrMainPath :: sxrTestPath :: Nil) from mainSources +++ testSources) {
      sxrEnabled.withValue(true) {
        update.run orElse
          clean.run orElse
          updateSxrLinks orElse
          testCompile.run orElse
          // create directory to avoid clean-recompile loop on no sources
          FileUtilities.createDirectory(sxrMainPath, log) orElse
          FileUtilities.createDirectory(sxrTestPath, log)
      }
    }
}

trait Publish extends Write {
  import Utils._
  import dispatch.mime.Mime._
  /** Organization we'll attempt to publish to, defaults to `organization` */
  def sxrOrg = organization
  /** Project name to publish, defaults to normalizedName */
  def sxrName = normalizedName
  /** Current version with any -SNAPSHOT suffix removed */
  def sxrVersion = "-SNAPSHOT$".r.replaceFirstIn(version.toString, "")
  /** Secret for the sxrOrg, defaults to property defined in the host config file */
  def sxrSecret = getSxrProperty(sxrOrg)

  lazy val previewSxr = previewSxrAction describedAs "Write sxr annotated sources and open in browser"
  def previewSxrAction = task { 
    tryBrowse(indexFile(sxrMainPath).asFile.toURI, false) orElse {
      if (indexFile(sxrTestPath).exists)
        tryBrowse(indexFile(sxrTestPath).asFile.toURI, false)
      else None
    }
  } dependsOn writeSxr

  /** Where to find sxrSecret, defaults to ~/.<sxrHostname> */
  def sxrCredentialsPath = Path.userHome / ("." + sxrHostname)
  /** Target path on the server */
  def sxrPublishMainPath = sxrHost / sxrOrg / sxrName / sxrVersion
  def sxrPublishTestPath = sxrHost / sxrOrg / (sxrName + ".test") / sxrVersion

  /** Publish to the given path, returns None if successful, or Some(errorstring) */
  def publish(src: Path, dest: Request): Option[String] = try {
    log.info("Publishing " + src)
    val SHA1 = "HmacSHA1"
    implicit def str2bytes(str: String) = str.getBytes("utf8")
    val key = new crypto.spec.SecretKeySpec(sxrSecret, SHA1)
    val mac = crypto.Mac.getInstance(SHA1)
    mac.init(key)
    val destFile = dest / src.relativePath
    mac.update(destFile.to_uri.toString)
    FileUtilities.readBytes(src.asFile, log).fold({ err => Some(err) }, { bytes =>
      val sig = new String(encodeBase64(mac.doFinal(bytes)))
      val Ext(ext) = src.name
      http(destFile << Map.empty[String,String] >- { uploadPath =>
        http(uploadPath << Map("sig" -> sig) << ("file", src.asFile, contentType(ext)) >- { out =>
          log.info(out)
        })
      })
      None
    })
  } catch { case e => Some(e.toString) }

  lazy val publishSxr = 
    publishSxrAction(
      sxrMainPath, sxrPublishMainPath, Some(sxrMainPath / "link.index")
    ) && publishSxrAction(
      sxrTestPath, sxrPublishTestPath, None
    ) dependsOn writeSxr describedAs
      "Publish annotated, versioned project sources to %s".format(sxrHostname)

  def publishSxrAction(src: Path, dest: Request, srcIndex: Option[Path]) = task { 
    sxrCredentialReqs orElse {
      srcIndex foreach { si => FileUtilities.gzip(si, src / "link.index.gz", log) }
      val exts = "html" :: "js" :: "css" :: "index.gz" :: Nil
      val sources = exts map { e => descendents(src, "*." + e) } reduceLeft { _ +++ _ }
      val none: Option[String] = None
      (none /: sources.get) { (last, cur) =>
        last orElse publish(cur, dest)
      } orElse {
        if (indexFile(src).exists)
          tryBrowse(indexFile(dest).to_uri, true)
        else None
      }
    }
  }

  /** Return property from sxrCredentialsPath */
  private def getSxrProperty(name: String) = {
    val props = new java.util.Properties
    FileUtilities.readStream(sxrCredentialsPath.asFile, log){ input => props.load(input); None }
    props.getProperty(name, "")
  }

  def sxrCredentialReqs = ( missing(sxrCredentialsPath, "credentials file")
    ) orElse { missing(sxrSecret, sxrCredentialsPath, "%s secret" format sxrOrg) }
}

object Utils {
  /** Dispatch Http instance for retrieving links and publishing */
  def http = new Http with FollowAllRedirects
  /** To do: use upstream when available */
  trait FollowAllRedirects extends Http {
    override val client = new ConfiguredHttpClient with PermissiveRedirect
    trait PermissiveRedirect extends org.apache.http.impl.client.AbstractHttpClient {
      import org.apache.http.{HttpResponse,HttpStatus}
      import org.apache.http.protocol.HttpContext
      import HttpStatus._
      override def createRedirectHandler = new org.apache.http.impl.client.DefaultRedirectHandler {
        override def isRedirectRequested(res: HttpResponse, ctx: HttpContext) =
          (SC_MOVED_TEMPORARILY :: SC_MOVED_PERMANENTLY :: SC_TEMPORARY_REDIRECT :: SC_SEE_OTHER :: Nil) contains
            res.getStatusLine.getStatusCode
      }
    }
  }
  /** Force app-engine to use gzip; it won't with known UA */
  val gzip = (_: Request).gzip <:< Map("User-Agent" -> "gzip")

  def missing(path: Path, title: String) =
    Some(path) filter (!_.exists) map { ne =>
      "Missing %s, expected in %s" format (title, path)
    }

  def missing(str: String, path: Path, title: String) = 
    Some(str) filter { _ == "" } map { str =>
      "Missing value %s in %s" format (title, path)
    }
  /** @return index.html file nested under the given path */
  def indexFile[T](p: { def / (f: String): T }) = p / "index.html"
  /** Opens uri in a browser if on a JVM 1.6+ */
  def tryBrowse(uri: URI, quiet: Boolean) = {
    try {
      val dsk = Class.forName("java.awt.Desktop")
      dsk.getMethod("browse", classOf[java.net.URI]).invoke(
        dsk.getMethod("getDesktop").invoke(null), uri
      )
      None
    } catch { case e => if(quiet) None else Some("Error trying to preview notes:\n\t" + rootCause(e).toString) }
  }
  def rootCause(e: Throwable): Throwable = if(e.getCause eq null) e else rootCause(e.getCause)
  val Ext = """.+\.([^\.]+)""".r
  val contentType = Map(
    "js"      -> "application/javascript",
    "html"    -> "text/html",
    "gz"      -> "application/x-gzip",
    "css"     -> "text/css"
  )
}

/** Mix in to test against local server */
trait LocalTest extends Write {
  override def sxrHostname = "localhost"
  override def sxrHost = :/(sxrHostname, 8080)
}
