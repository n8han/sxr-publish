package sxr

import sbt._
import dispatch._
import java.net.URI
import javax.crypto
import org.apache.commons.codec.binary.Base64.encodeBase64

trait Write extends BasicScalaProject {
  def sxr_version = "0.2.4"
  val sxr = "org.scala-tools.sxr" %% "sxr" % sxr_version % "sxr->default(compile)"
  def sxrMainPath = outputPath / "classes.sxr"
  def indexFile(p: Path) = p / "index.html"
  def sxrTestPath = outputPath / "test-classes.sxr"

  val sxrConf = Configurations.config("sxr")
  def sxrFinder = configurationPath(sxrConf) * "*"
  protected def sxrOption = sxrFinder.get.filter { f => sxrEnabled } map { p =>
    new CompileOption("-Xplugin:" + p.absolutePath)
  } toList
  abstract override def compileOptions = sxrOption ++ super.compileOptions
  private var sxrEnabled = false

  lazy val writeSxr = writeSxrAction
  def writeSxrAction = fileTask(sxrMainPath from mainSources) {
    sxrEnabled = true
    clean.run orElse compile.run orElse {
      sxrEnabled = false
      None
    }
  }
}

trait Publish extends Write {
  def sxrOrg = organization
  def sxrName = normalizedName
  def sxrVersion = version.toString
  def sxrSecret = getSxrProperty(sxrOrg)

  lazy val previewSxr = previewSxrAction
  def previewSxrAction = task { 
    tryBrowse(indexFile(sxrMainPath).asFile.toURI, false)
  } dependsOn writeSxr

  lazy private val http = new dispatch.Http
  def sxrHost = :/("localhost", 8080)
  def publish(path: Path): Option[String] = try {
    log.info("Publishing " + path)
    val SHA1 = "HmacSHA1";
    implicit def str2bytes(str: String) = str.getBytes("utf8")
    val key = new crypto.spec.SecretKeySpec(sxrSecret, SHA1)
    val target = sxrHost / sxrOrg / sxrName / sxrVersion / path.name
    val mac = crypto.Mac.getInstance(SHA1)
    mac.init(key)
    mac.update(target.to_uri.toString)
    scala.io.Source.fromFile(path.asFile).getLines foreach { l =>
      mac.update(l)
    }
    val sig = new String(encodeBase64(mac.doFinal()))
    http(target <<? Map("sig" -> sig) <<< path.asFile >|)
    None
  } catch { case e => Some(e.getMessage) }

  lazy val publishSxr = publishSxrAction
  def publishSxrAction = task { credentialReqs orElse {
    val none: Option[String] = None
    (none /: descendents(sxrMainPath, "*.html").get) { (last, cur) =>
      last orElse publish(cur)
    }
  } } dependsOn writeSxr


  def sxrCredentialsPath = Path.userHome / ".sxr_publish"
  private def getSxrProperty(name: String) = {
    val props = new java.util.Properties
    FileUtilities.readStream(sxrCredentialsPath.asFile, log){ input => props.load(input); None }
    props.getProperty(name, "")
  }

  def credentialReqs = ( missing(sxrCredentialsPath, "credentials file")
    ) orElse { missing(sxrSecret, sxrCredentialsPath, "%s secret" format sxrOrg) }

  def missing(path: Path, title: String) =
    Some(path) filter (!_.exists) map { ne =>
      "Missing %s, expected in %s" format (title, path)
    }

  def missing(str: String, path: Path, title: String) = 
    Some(str) filter { _ == "" } map { str =>
      "Missing value %s in %s" format (title, path)
    }
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
  private def rootCause(e: Throwable): Throwable = if(e.getCause eq null) e else rootCause(e.getCause)
}
