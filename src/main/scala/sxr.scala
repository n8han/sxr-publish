package sxr

import sbt._

trait Write extends BasicScalaProject {
  def sxr_version = "0.2.4"
  val sxr = "org.scala-tools.sxr" %% "sxr" % sxr_version % "sxr->default(compile)"
  def sxrMainPath = outputPath / "classes.sxr"
  def sxrTestPath = outputPath / "test-classes.sxr"

  val sxrConf = Configurations.config("sxr")
  def sxrFinder = configurationPath(sxrConf) * "*"
  protected def sxrOption = sxrFinder.get.filter { f => sxrEnabled } map { p =>
    new CompileOption("-Xplugin:" + p.absolutePath)
  } toList
  abstract override def compileOptions = sxrOption ++ super.compileOptions
  private var sxrEnabled = false
  def setSxr(b: Boolean) = task { sxrEnabled = b; None }

  lazy val writeSxr = writeSxrAction
  def writeSxrAction = setSxr(true) && clean && compile && setSxr(false)
}

trait Publish extends Write {
  def sxrOrg = organization
  def sxrSecret = getSxrProperty(sxrOrg)

  lazy val publishSxr = publishSxrAction
  def publishSxrAction = task { credentialReqs orElse {
    None
  } } dependsOn compile


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
}
