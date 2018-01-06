package com.carrotgarden.maven.scalor

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations._
import com.carrotgarden.maven.tools.Description

import A.mojo._
import A.extend._

import util.OSGI._
import util.Error._
import util.Params._
import util.Classer._
import util.Props._

import eclipse.Wiring._
import eclipse.Plugin

import base.Params._

import scala.util.Success
import scala.util.Failure

import scala.collection.JavaConverters._

import org.eclipse.m2e.core.ui.internal.UpdateMavenProjectJob
import org.eclipse.core.resources.IProject

/**
 * Shared Eclipse mojo interface.
 */
trait EclipseAnyMojo extends base.Mojo {

  @Description( """
  Flag to skip goal execution: <code>eclipse-*</code>.
  """ )
  @Parameter(
    property     = "scalor.skipEclipse", //
    defaultValue = "false"
  )
  var skipEclipse : Boolean = _

  @Description( """
  Invoke Eclipse configuration executions only when runnting inside Eclipse/M2E.
  When <code>false</code>, force executions regardless of the Eclipse detection state.
  """ )
  @Parameter(
    property     = "scalor.eclipseDetectPresent", //
    defaultValue = "true"
  )
  var eclipseDetectPresent : Boolean = _

  /**
   * Connect this Maven plugin with host Eclipse plugins.
   */
  def reportHandle = wiringHandle match {
    case Success( handle ) =>
      say.info( "Using Eclipse platform plugins:" )
      say.info( s"   ${handle.resourcesPlugin.getBundle}" )
      say.info( s"   ${handle.mavenPlugin.getBundle}" )
      // TODO remove dependency on m2e.core.ui/UpdateMavenProjectJob
      say.info( s"   ${handle.mavenPluginUI.getBundle}" )
    case Failure( error ) =>
      say.error( "Required Eclipse plugin is missing: " + error )
  }

  /**
   * Connect this Maven plugin with host Eclipse plugins.
   */
  def resolveHandle = wiringHandle match {
    case Success( handle ) =>
      handle
    case Failure( error ) =>
      throw error
  }

  /**
   * Eclipse mojo business logic.
   */
  def performEclipse : Unit

  override def perform() : Unit = {
    if ( skipEclipse ) {
      reportSkipReason( "Skipping disabled goal execution." )
      return
    }
    if ( eclipseDetectPresent && !hasEclipse ) {
      reportSkipReason( "Skipping non-eclipse build invocation." )
      return
    }
    if ( hasIncremental ) {
      reportSkipReason( "Skipping incremental build invocation." )
      return
    }
    reportHandle
    resolveHandle
    performEclipse
  }

}

@Description( """
Install companion Eclipse plugin provided by this Maven plugin when running under Eclipse/M2E.
""" )
@Mojo(
  name                         = `eclipse-config`,
  defaultPhase                 = LifecyclePhase.INITIALIZE,
  requiresDependencyResolution = ResolutionScope.NONE
)
class EclipseConfigMojo extends EclipseAnyMojo
  with base.ParamsCompiler
  with eclipse.Params {

  override def mojoName = `eclipse-config`

  override def performEclipse = {
    say.info( "Configuring companion Eclipse plugin:" )
    val handle = resolveHandle

    val descriptorUrl = Plugin.Config.pluginPropertiesUrl
    val descriptorProps = propertiesFrom( descriptorUrl )

    val pluginId = descriptorProps.getProperty( Plugin.Config.key.pluginId )
    val pluginLocation = Plugin.Config.location
    say.info( "   pluginId: " + pluginId )
    say.info( "   location: " + pluginLocation )

    val context = handle.bundleM2E.getBundleContext

    val pluginOption = Option( context.getBundle( pluginLocation ) )

    val ( message, pluginBundle, hasProjectUpdate ) = if ( pluginOption.isDefined ) {
      ( "Companion plugin was previously installed", pluginOption.get, false )
    } else {
      context.installBundle( pluginLocation ).start()
      ( "Companion plugin is now installed in Eclipse", context.getBundle( pluginLocation ), true )
    }

    say.info( message + ": " + pluginBundle )

    if ( hasProjectUpdate ) {
      say.info( "Scheduling project update in Eclipse to invoke M2E project configurator." )
      val projectList = handle.workspace.getRoot.getProjects
      val currentProject = projectWithBase( projectList, project.getBasedir )
      // TODO remove dependency on m2e.core.ui/UpdateMavenProjectJob
      val updateJob = new UpdateMavenProjectJob( Array[ IProject ]( currentProject ) )
      val updateName = "Project update for: " + project.getArtifactId + " by " + pluginId
      updateJob.setName( updateName )
      updateJob.setPriority( 10 ) // interactive
      updateJob.schedule( 1 * 1000 ) // start delay, seconds
    }

  }

}