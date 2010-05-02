package sxr

import sbt._

trait Publish extends BasicScalaProject with AutoCompilerPlugins {
  def sxr_version = "0.2.4"
  val sxr = compilerPlugin("org.scala-tools.sxr" %% "sxr" % sxr_version)
  def sxrMainPath = outputPath / "classes.sxr"
  def sxrTestPath = outputPath / "test-classes.sxr"

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
