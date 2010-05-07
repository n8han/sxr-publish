package sxr

import sbt._
import java.net.URI

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
  def sxrSecret = getSxrProperty(sxrOrg)

  lazy val previewSxr = previewSxrAction
  def previewSxrAction = task { 
    tryBrowse(indexFile(sxrMainPath).asFile.toURI, false)
  } dependsOn writeSxr

  lazy val publishSxr = publishSxrAction
  def publishSxrAction = task { credentialReqs orElse {
    None
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
