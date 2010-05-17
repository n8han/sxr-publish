package sxr

import sbt._
import dispatch._
import java.net.URI
import javax.crypto
import org.apache.commons.codec.binary.Base64.encodeBase64

trait Write extends BasicScalaProject {
  /** Override to define a particular sxr artifact */
  def sxr_artifact = "org.scala-tools.sxr" %% "sxr" % "0.2.4"
  /** Custom config, to keep sxr's jar separate and hide the dependency when publishing */
  lazy val SxrPlugin = (new Configuration("sxr")) hide
  /** Artifact assigned to SxrPlugin configuration */
  lazy val sxr = sxr_artifact % "%s->default(compile)".format(SxrPlugin.name)
  abstract override def extraDefaultConfigurations = SxrPlugin :: super.extraDefaultConfigurations
  /** Output path of the compiler plugin, does not control the path but should reflect it */
  def sxrMainPath = outputPath / "classes.sxr"
  /** Output path of the compiler plugin's test sources, not currently used */
  def sxrTestPath = outputPath / "test-classes.sxr"

  /** Custom sxr configuration, so that sxr is not on the regular compile path */
  val sxrConf = Configurations.config("sxr")
  /** Select the jar in the sxr configuration path */
  def sxrFinder = configurationPath(sxrConf) * "*.jar"
  /** Returns sxr as a compiler option only if sxrEnabled is set to true */
  protected def sxrOption = sxrFinder.get.filter { f => sxrEnabled } map { p =>
    new CompileOption("-Xplugin:" + p.absolutePath)
  } toList
  /** Adds in whatever is returned by sxrOption */
  abstract override def compileOptions = sxrOption ++ super.compileOptions
  /** Variable used to enable the sxr plugin for the duration of tasks requiring it */
  private var sxrEnabled = false

  lazy val writeSxr = writeSxrAction describedAs "Clean and re-compile with the sxr plugin enabled, writes annotated sources"
  def writeSxrAction = fileTask(sxrMainPath from mainSources) {
    sxrEnabled = true
    clean.run orElse compile.run orElse {
      sxrEnabled = false
      None
    }
  }
}

trait Publish extends Write {
  /** Organization we'll attempt to publish to, defaults to `organization` */
  def sxrOrg = organization
  /** Project name to publish, defaults to normalizedName */
  def sxrName = normalizedName
  /** Version string to publish, defaults to version.toString */
  def sxrVersion = version.toString
  /** Secret for the sxrOrg, defaults to property defined in the host config file */
  def sxrSecret = getSxrProperty(sxrOrg)

  lazy val previewSxr = previewSxrAction describedAs "Write sxr annotated sources and open in browser"
  def previewSxrAction = task { 
    Publish.tryBrowse(Publish.indexFile(sxrMainPath).asFile.toURI, false)
  } dependsOn writeSxr

  /** Dispatch Http instance to be used when publishing */
  lazy private val http = new dispatch.Http

  /** Publishing target host, defaults to "sourced.implicit.ly" */
  def sxrHostname = "sourced.implicit.ly"
  /** Defaults to sxrHostname on port 80 */
  def sxrHost = :/(sxrHostname)
  /** Where to find sxrSecret, defaults to ~/.<sxrHostname> */
  def sxrCredentialsPath = Path.userHome / ("." + sxrHostname)
  /** Target path on the server */
  def sxrPublishPath = sxrHost / sxrOrg / sxrName / sxrVersion

  /** Publish to the given path, returns None if successful, or Some(errorstring) */
  def publish(path: Path): Option[String] = try {
    log.info("Publishing " + path)
    val SHA1 = "HmacSHA1"
    implicit def str2bytes(str: String) = str.getBytes("utf8")
    val key = new crypto.spec.SecretKeySpec(sxrSecret, SHA1)
    val mac = crypto.Mac.getInstance(SHA1)
    mac.init(key)
    val filePath = sxrPublishPath / path.name
    mac.update(filePath.to_uri.toString)
    scala.io.Source.fromFile(path.asFile).getLines foreach { l =>
      mac.update(l)
    }
    val sig = new String(encodeBase64(mac.doFinal()))
    http(filePath <<? Map("sig" -> sig) <<< (path.asFile, "text/plain") >|)
    None
  } catch { case e => Some(e.getMessage) }

  lazy val publishSxr = publishSxrAction describedAs "Publish annotated, versioned project sources to %s".format(sxrHostname)
  def publishSxrAction = task { sxrCredentialReqs orElse {
    val none: Option[String] = None
    val exts = "html" :: "js" :: "css" :: Nil
    val sources = exts map { e => descendents(sxrMainPath, "*." + e) } reduceLeft { _ +++ _ }
    (none /: sources.get) { (last, cur) =>
      last orElse publish(cur)
    } orElse {
      Publish.tryBrowse(Publish.indexFile(sxrPublishPath).to_uri, true)
    }
  } } dependsOn writeSxr

  /** Return property from sxrCredentialsPath */
  private def getSxrProperty(name: String) = {
    val props = new java.util.Properties
    FileUtilities.readStream(sxrCredentialsPath.asFile, log){ input => props.load(input); None }
    props.getProperty(name, "")
  }

  def sxrCredentialReqs = ( Publish.missing(sxrCredentialsPath, "credentials file")
    ) orElse { Publish.missing(sxrSecret, sxrCredentialsPath, "%s secret" format sxrOrg) }
}

object Publish {
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
}
