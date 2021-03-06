package com.carrotgarden.maven.scalor

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations._
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugin.MojoFailureException
import org.sonatype.plexus.build.incremental.BuildContext

import scala.collection.JavaConverters._

import java.util.Date
import java.io.File

import A.mojo._
import util.Folder._

import com.carrotgarden.maven.tools.Description
import java.util.Arrays

/**
 * Shared clean mojo interface.
 */
trait CleanAnyMojo extends AbstractMojo
  with base.Mojo
  with base.Params
  with base.Logging
  with base.SkipMojo {

  @Description( """
  Flag to skip goal execution: <code>clean-*</code>.
  """ )
  @Parameter(
    property     = "scalor.skipClean",
    defaultValue = "false"
  )
  var skipClean : Boolean = _

  def resouceList : List[ File ]

  def performClean() : Unit = {
    resouceList.foreach { file =>
      val path = file.getCanonicalFile
      logger.info( s"Deleting ${path}" )
      path.delete
    }
  }

  override def perform() : Unit = {
    if ( skipClean || hasSkipMojo ) {
      reportSkipReason( "Skipping disabled goal execution." )
      return
    }
    if ( hasIncremental ) {
      reportSkipReason( "Skipping incremental build invocation." )
      return
    }
    performClean()
  }

}

@Description( """
Clean project cache resources for all compilation scopes.
Invokes goals: clean-*
""" )
@Mojo(
  name                         = A.mojo.`clean`,
  defaultPhase                 = LifecyclePhase.CLEAN,
  requiresDependencyResolution = ResolutionScope.NONE
)
class CleanArkonMojo extends CleanAnyMojo
  with zinc.ParamsMacroCache
  with zinc.ParamsMainCache
  with zinc.ParamsTestCache {

  override def mojoName = A.mojo.`clean`

  override def resouceList = throwNotUsed

  override def performClean() : Unit = {
    executeSelfMojo( A.mojo.`clean-macro` )
    executeSelfMojo( A.mojo.`clean-main` )
    executeSelfMojo( A.mojo.`clean-test` )
  }

}

@Description( """
Clean project cache resources for compilation scope=macro.
A member of goal=clean.
""" )
@Mojo(
  name                         = A.mojo.`clean-macro`,
  defaultPhase                 = LifecyclePhase.CLEAN,
  requiresDependencyResolution = ResolutionScope.NONE
)
class CleanMacroMojo extends CleanAnyMojo
  with zinc.ParamsMacroCache {

  override def mojoName = A.mojo.`clean-macro`

  @Description( """
  Flag to skip goal execution: <code>clean-macro</code>.
  """ )
  @Parameter(
    property     = "scalor.skipCleanMacro",
    defaultValue = "false"
  )
  var skipCleanMacro : Boolean = _

  override def hasSkipMojo = skipCleanMacro
  override def resouceList : List[ File ] = List( zincCacheMacro )

}

@Description( """
Clean project cache resources for compilation scope=main.
A member of goal=clean.
""" )
@Mojo(
  name                         = A.mojo.`clean-main`,
  defaultPhase                 = LifecyclePhase.CLEAN,
  requiresDependencyResolution = ResolutionScope.NONE
)
class CleanMainMojo extends CleanAnyMojo
  with zinc.ParamsMainCache {

  override def mojoName = A.mojo.`clean-main`

  @Description( """
  Flag to skip goal execution: <code>clean-main</code>.
  """ )
  @Parameter(
    property     = "scalor.skipCleanMain",
    defaultValue = "false"
  )
  var skipCleanMain : Boolean = _

  override def hasSkipMojo = skipCleanMain
  override def resouceList : List[ File ] = List( zincCacheMain )

}

@Description( """
Clean project cache resources for compilation scope=test.
A member of goal=clean.
""" )
@Mojo(
  name                         = A.mojo.`clean-test`,
  defaultPhase                 = LifecyclePhase.CLEAN,
  requiresDependencyResolution = ResolutionScope.NONE
)
class CleanTestMojo extends CleanAnyMojo
  with zinc.ParamsTestCache {

  override def mojoName = A.mojo.`clean-test`

  @Description( """
  Flag to skip goal execution: <code>clean-test</code>.
  """ )
  @Parameter(
    property     = "scalor.skipCleanTest", //
    defaultValue = "false"
  )
  var skipCleanTest : Boolean = _

  override def hasSkipMojo = skipCleanTest
  override def resouceList : List[ File ] = List( zincCacheTest )

}
