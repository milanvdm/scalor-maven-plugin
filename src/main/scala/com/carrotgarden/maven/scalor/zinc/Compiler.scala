package com.carrotgarden.maven.scalor.zinc

import java.io.File
import java.net.URLClassLoader
import java.util.Optional

import com.carrotgarden.maven.scalor.base
import com.carrotgarden.maven.scalor.meta
import com.carrotgarden.maven.scalor.util

import sbt.internal.inc.AnalyzingCompiler
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.IncrementalCompilerImpl
import sbt.internal.inc.Locate
import sbt.internal.inc.LoggedReporter
import sbt.internal.inc.ScalaInstance
import sbt.util.InterfaceUtil
import sbt.util.Level
import sbt.util.Logger
import xsbti.Problem
import xsbti.compile.AnalysisContents
import xsbti.compile.AnalysisStore
import xsbti.compile.ClasspathOptionsUtil
import xsbti.compile.CompileAnalysis
import xsbti.compile.CompileOrder
import xsbti.compile.CompileProgress
import xsbti.compile.CompilerCache
import xsbti.compile.DefinesClass
import xsbti.compile.IncOptions
import xsbti.compile.MiniSetup
import xsbti.compile.PerClasspathEntryLookup
import xsbti.compile.PreviousResult
import xsbti.compile.ZincCompilerUtil

/**
 * Compiler for scope=macro.
 */
trait CompilerMacro extends Compiler
  with base.BuildMacro
  with ParamsMacro {

  self : Resolve //
  with base.Build with base.Logging with base.Params with base.ParamsDefine //
  =>

  def zincBuildCache = zincCacheMacro
}

/**
 * Compiler for scope=main.
 */
trait CompilerMain extends Compiler
  with base.BuildMain
  with ParamsMain {

  self : Resolve //
  with base.Build with base.Logging with base.Params with base.ParamsDefine //
  =>

  def zincBuildCache = zincCacheMain
}

/**
 * Compiler for scope=test.
 */
trait CompilerTest extends Compiler
  with base.BuildTest
  with ParamsTest {

  self : Resolve //
  with base.Build with base.Logging with base.Params with base.ParamsDefine //
  =>

  def zincBuildCache = zincCacheTest
}

/**
 * Shared compiler interface.
 */
trait Compiler {

  self : Params with Resolve //
  with base.Build with base.Logging with base.Params with base.ParamsDefine //
  =>

  import Compiler._

  /**
   *
   * Incremental compiler state for a scope.
   */
  def zincBuildCache : File

  /**
   * Compilation scope input source files.
   */
  def zincBuildSources : Array[ File ] = {
    import util.Folder._
    val zincRegexAnySource = zincRegexAnyJava + "|" + zincRegexAnyScala
    fileListByRegex( buildSourceFolders, zincRegexAnySource )
  }

  /**
   * Compilation scope classes output directory.
   */
  def zincBuildTarget : File = buildTargetFolder

  /**
   * Configured project build dependencies.
   */
  def zincBuildClassPath : Array[ File ] =
    buildDependencyFolders ++ projectClassPath( buildDependencyScopes )

  /**
   * Verify if logging is enabled at a level.
   */
  def zincHasLog( level : Level.Value ) : Boolean = {
    level.id >= zincLogActiveLevel
  }

  /**
   * Incremental compiler file analysis store.
   */
  def zincStateStore( cacheFile : File ) : AnalysisStore = {
    storeType( zincStateStoreType ) match {
      case Store.Text =>
        FileAnalysisStore.text( cacheFile )
      case Store.Binary =>
        FileAnalysisStore.binary( cacheFile )
      case _ =>
        val param = meta.Macro.nameOf( zincStateStoreType )
        val value = zincStateStoreType
        say.error( s"Unknown store type ${param}=${value}, using 'binary'." )
        FileAnalysisStore.binary( cacheFile )
    }
  }

  /**
   * Setup and invoke Zinc incremental compiler.
   */
  def zincPerformCompile() : Unit = {
    import util.Folder._

    // Assemble required build context.
    val buildSources : Array[ File ] =
      zincBuildSources.map( ensureCanonicalFile( _ ) )
    val buildClassPath : Array[ File ] =
      zincBuildClassPath.map( ensureCanonicalFile( _ ) )
    val buildCacheFile : File =
      ensureCanonicalFile( zincBuildCache )
    val buildOutputFolder : File =
      ensureCanonicalFile( zincBuildTarget )

    // Ensure output locations.
    ensureParent( buildCacheFile )
    ensureFolder( buildOutputFolder )

    // Provide compiler installation.
    val compilerInstall = resolveCustomInstall()
    val compilerClassPath : Array[ File ] =
      compilerInstall.zincJars.map( Module.fileFrom( _ ) ).toArray
    val compilerPluginList : Array[ File ] =
      compilerInstall.pluginDefineList.map( Module.fileFrom( _ ) ).toArray

    // Provide compiler class loader.
    val compilerLoader : ClassLoader = {
      val entryList = compilerClassPath.map( _.toURI.toURL )
      new URLClassLoader( entryList )
    }

    // Provide compiler options.
    val optionsConfig = Settings.extract(
      compilerInstall.version, parseCompileOptions, say.error
    )

    // Provide user reporting.
    if ( zincLogSourcesList ) {
      say.info( "Sources list:" )
      reportFileList( buildSources )
    }
    if ( zincLogProjectClassPath ) {
      say.info( "Build class path:" )
      reportFileList( buildClassPath )
    }
    if ( zincLogCompilerClassPath ) {
      say.info( "Compiler class path:" )
      reportFileList( compilerClassPath )
    }
    if ( zincLogCompilerPluginList ) {
      say.info( "Compiler plugin list:" )
      reportFileList( compilerPluginList )
    }
    if ( zincLogCompileOptions ) {
      say.info( "Compiler options report:" )
      val reportFile = zincComileOptionsReport
      val reportText = optionsConfig.reportFun()
      ensureParent( reportFile )
      persistString( reportFile, reportText )
      say.info( s"   ${reportFile}" )
    }

    // Verify dependency version consistency.
    if ( zincVerifyVersion ) {
      Version.assertVersion( compilerInstall )
    }

    // Final compilation options.
    val pluginOptions = compilerPluginList.flatMap( pluginStanza( _ ) )
    val scalacOptions = optionsConfig.standard ++ pluginOptions
    val javacOptions = Array.empty[ String ] // not used
    val compileOrder = CompileOrder.valueOf( optionsConfig.compileOrder )
    val maxErrors = optionsConfig.maxErrors

    // Provide Zinc ScalaC instance.
    val scalaInstance = instanceFrom( compilerLoader, compilerInstall )
    val bridgeJar = Module.fileFrom( compilerInstall.bridge )

    // Provide static pre-built zinc compiler-bridge jar.
    val provider = ZincCompilerUtil.constantBridgeProvider( scalaInstance, bridgeJar )

    // Configured ScalaC instance.
    val scalaCompiler = new AnalyzingCompiler(
      scalaInstance    = scalaInstance,
      provider         = provider,
      classpathOptions = ClasspathOptionsUtil.auto,
      onArgsHandler = _ => (),
      classLoaderCache = None // FIXME provide loader cache
    )

    // Use zinc incremental compiler.
    val incremental = new IncrementalCompilerImpl

    val compilers = incremental.compilers(
      scalaInstance, ClasspathOptionsUtil.boot, None, scalaCompiler
    )

    val lookup = new PerClasspathEntryLookup {
      override def analysis( classpathEntry : File ) : Optional[ CompileAnalysis ] = Optional.empty[ CompileAnalysis ]
      override def definesClass( classpathEntry : File ) : DefinesClass = Locate.definesClass( classpathEntry )
    }

    // Use zinc invocation logger.
    val logger = new Logger {
      override def trace( error : => Throwable ) : Unit =
        say.info( "[TRCE] " + Option( error.getMessage ).getOrElse( "error" ) )
      override def success( message : => String ) : Unit =
        say.info( "[DONE] " + s"Success: $message" )
      override def log( level : Level.Value, message : => String ) : Unit =
        level match {
          case Level.Debug => if ( zincHasLog( Level.Debug ) ) say.info( "[DBUG] " + message )
          case Level.Info  => if ( zincHasLog( Level.Info ) ) say.info( "[INFO] " + message )
          case Level.Warn  => if ( zincHasLog( Level.Warn ) ) say.info( "[WARN] " + message )
          case Level.Error => if ( zincHasLog( Level.Error ) ) {
            if ( message.contains( "\n" ) ) { // suppress stack dump
              say.error( "[FAIL] " + message.substring( 0, message.indexOf( "\n" ) ) )
            } else {
              say.error( "[FAIL] " + message )
            }
          }
        }
    }

    // Compilation problem reporter.
    val reporter = new LoggedReporter( maxErrors, logger ) {
      override def logInfo( problem : Problem ) : Unit =
        logger.info( problem.toString )
      override def logWarning( problem : Problem ) : Unit =
        logger.warn( problem.toString )
      override def logError( problem : Problem ) : Unit =
        logger.error( problem.toString )
    }

    // Compilation progress printer.
    val progress = new CompileProgress {
      override def startUnit( phase : String, unitPath : String ) : Unit = {
        if ( zincLogProgressUnit ) say.info( s"[INIT] ${phase} / ${unitPath}" )
      }
      override def advance( current : Int, total : Int ) : Boolean = {
        if ( zincLogProgressRate ) say.info( s"[STEP] ${current} / ${total}" )
        true
      }
    }

    // Incremental compiler setup.
    val setup = incremental.setup(
      lookup         = lookup,
      skip           = false,
      cacheFile      = buildCacheFile,
      cache          = CompilerCache.fresh,
      incOptions     = IncOptions.of(),
      reporter       = reporter,
      optionProgress = Some( progress ),
      extra          = Array.empty
    )

    // Extract past state.
    val storePast = zincStateStore( buildCacheFile )
    val storeNext = AnalysisStore.getCachedStore( storePast )

    //    val contentPast = storePast.get()
    //    val resultPast = PreviousResult
    //      .of( contentPast.map( _.getAnalysis ), contentPast.map( _.getMiniSetup ) )

    // Iterative inputs.
    val inputsPast = incremental.inputs(
      classpath             = buildClassPath,
      sources               = buildSources,
      classesDirectory      = buildOutputFolder,
      scalacOptions         = scalacOptions,
      javacOptions          = javacOptions,
      maxErrors             = maxErrors,
      sourcePositionMappers = Array.empty,
      order                 = compileOrder,
      compilers             = compilers,
      setup                 = setup,
      pr                    = incremental.emptyPreviousResult
    //      pr                    = resultPast
    )

    // Iterative inputs.
    val inputsNext = {
      InterfaceUtil.toOption( storeNext.get() ) match {
        case Some( contentPast ) =>
          val analysisPast = contentPast.getAnalysis
          val setupPast = contentPast.getMiniSetup
          val resultPast = PreviousResult.of(
            Optional.of[ CompileAnalysis ]( analysisPast ),
            Optional.of[ MiniSetup ]( setupPast )
          )
          inputsPast.withPreviousResult( resultPast )
        case _ =>
          inputsPast
      }
    }

    say.info( s"Invoking Zinc compiler: ${scalaInstance.version}" )

    // Run compiler invocation.
    val resultNext = incremental.compile( inputsNext, logger )

    // Persist next state.
    val contentNext = AnalysisContents.create( resultNext.analysis, resultNext.setup )
    storeNext.set( contentNext )

  }

}

object Compiler {
  import util.Folder._
  import Module._

  /**
   * Scala compiler argument: plugin stanza: activate plugin by jar path.
   */
  def pluginStanza( file : File ) = Array[ String ]( "-Xplugin", ensureCanonicalPath( file ) )

  /**
   * Convert into Zinc scala compiler installation format.
   */
  def instanceFrom( loader : ClassLoader, install : ScalaInstall ) : ScalaInstance = {
    import install._
    new ScalaInstance(
      version        = version.unparse,
      loader         = loader,
      libraryJar     = fileFrom( library ),
      compilerJar    = fileFrom( compiler ),
      allJars        = zincJars.map( fileFrom( _ ) ).toArray,
      explicitActual = Some( version.unparse )
    )
  }

  object Store {
    sealed trait Type
    case object Text extends Type
    case object Binary extends Type
    case object Unknown extends Type
  }

  def storeType( name : String ) = name match {
    case "text"   => Store.Text
    case "binary" => Store.Binary
    case _        => Store.Unknown
  }

}
