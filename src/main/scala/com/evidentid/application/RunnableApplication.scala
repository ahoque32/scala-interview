package com.evidentid.application

import akka.actor.{ActorSystem, Terminated}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import com.evidentid.application.rates.RatesProviderManager
import com.evidentid.application.rates.api.RatesProviderRoute
import com.evidentid.application.status.HealthCheckManager
import com.evidentid.application.status.api.HealthCheckRoute
import com.evidentid.database.DatabaseManager

import com.evidentid.http.server.api.DocsRoute
import com.evidentid.logging.Logging
import com.typesafe.config.Config
// Added imports for DB query
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.GetResult // Added import for GetResult
import scala.concurrent.Await
import scala.concurrent.duration._
import sttp.tapir.server.akkahttp.AkkaHttpServerOptions

import java.net.InetSocketAddress
import java.time.{Clock, Instant}
import java.util.UUID
import java.sql.Timestamp
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class RunnableApplication(
  val config: Config,
  val actorSystemName: String,
  val bindInterface: String,
  val bindPort: Int,
  val databaseConfig: DatabaseManager.DatabaseConnectionConfig
) extends Application
    with Logging {

  private var httpServerBinding: Future[InetSocketAddress] = _
  override lazy val system: ActorSystem = ActorSystem(actorSystemName, config)
  private lazy val ioExecutionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  override def start(): Future[Terminated] = {

    val appSetupStartTime = System.currentTimeMillis()
    logger.info("Starting Database setup")
    val databaseManager = setupDatabaseManager(databaseConfig)(system)
    val dbSetupDoneTime = System.currentTimeMillis()
    logger.info(s"Database setup done in ${dbSetupDoneTime - appSetupStartTime}ms, starting HTTP server")
    setupHttpServer(databaseManager)(system)
    val httpServerStartedTime = System.currentTimeMillis()
    logger.info(s"HTTP server started in ${httpServerStartedTime - dbSetupDoneTime}")
    val appSetupFinishedTime = System.currentTimeMillis()
    logger.info(s"Application started properly, total startup time: ${appSetupFinishedTime - appSetupStartTime}ms")

    system.whenTerminated
  }

  def setupDatabaseManager(databaseConfig: DatabaseManager.DatabaseConnectionConfig)(implicit system: ActorSystem): DatabaseManager = {
    logger.info("Creating database manager")
    val databaseManager = DatabaseManager.create(databaseConfig, disableFlywayHistoryClean = true)
    logger.info("Starting Flyway DB migration")
    databaseManager.migrate()
    logger.info("Database Flyway DB migration done, adding database shutdown task to coordinated shutdown")

    // Print flyway_schema_history content
    logger.info("--- Flyway Schema History ---")
    // system.dispatcher will be implicitly available for Await and potentially for Slick's run if needed by its specific signature
    // If DatabaseWrapper.run needs an explicit EC, it would be passed there.
    // For now, removing the explicit 'ec' val as it was flagged as unused and run should pick up an EC.
    try {
      val action = sql"SELECT version, description, type, script, checksum, installed_by, installed_on, execution_time, success FROM flyway_schema_history ORDER BY installed_rank".as[(Option[String], String, String, String, Option[Int], String, java.sql.Timestamp, Int, Boolean)]
      // Corrected: use databaseManager.database.run directly
      // Await.result itself needs an ExecutionContext, which system.dispatcher (imported as system.dispatcher) should provide implicitly.
      val results = Await.result(databaseManager.database.run(action), 10.seconds) // Blocking call
      if (results.isEmpty) {
        logger.info("(empty)")
      } else {
        results.foreach {
          case (version, description, typ, script, checksum, installed_by, installed_on, execution_time, success) =>
            logger.info(
              s"Version: ${version.getOrElse("N/A")}, Description: $description, Type: $typ, Script: $script, Checksum: ${checksum.getOrElse("N/A")}, InstalledBy: $installed_by, InstalledOn: $installed_on, ExecTime: ${execution_time}ms, Success: $success"
            )
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to query flyway_schema_history: ${e.getMessage}", e)
    }
    logger.info("--- End Flyway Schema History ---")

    // --- Print rates_providers content ---
    logger.info("--- Rates Providers Table ---")
    case class RateProviderRow(id: UUID, providerName: String, currencyCode: String, url: String, createdAt: Timestamp, modifiedAt: Timestamp)
    // system.dispatcher is already implicitly available from the ActorSystem 'system'
    try {
      // Define the mapping from SQL result columns to the case class fields
      implicit val getRateProviderResult: GetResult[RateProviderRow] = GetResult(r => RateProviderRow(UUID.fromString(r.nextString()), r.nextString(), r.nextString(), r.nextString(), r.nextTimestamp(), r.nextTimestamp()))
      val action = sql"SELECT id, provider_name, currency_code, url, created_at, modified_at FROM rates_providers".as[RateProviderRow]
      val results = Await.result(databaseManager.database.run(action), 10.seconds)
      if (results.isEmpty) {
        logger.info("(empty)")
      } else {
        results.foreach(row => logger.info(row.toString))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to query rates_providers: ${e.getMessage}", e)
    }
    logger.info("--- End Rates Providers Table ---")

    // --- Print archived_rates_providers content ---
    logger.info("--- Archived Rates Providers Table ---")
    case class ArchivedRateProviderRow(id: UUID, providerName: String, currencyCode: String, url: String, createdAt: Timestamp, modifiedAt: Timestamp, archivedAt: Timestamp)
    try {
      // Define the mapping from SQL result columns to the case class fields
      implicit val getArchivedRateProviderResult: GetResult[ArchivedRateProviderRow] = GetResult(r => ArchivedRateProviderRow(UUID.fromString(r.nextString()), r.nextString(), r.nextString(), r.nextString(), r.nextTimestamp(), r.nextTimestamp(), r.nextTimestamp()))
      val action = sql"SELECT id, provider_name, currency_code, url, created_at, modified_at, archived_at FROM archived_rates_providers".as[ArchivedRateProviderRow]
      val results = Await.result(databaseManager.database.run(action), 10.seconds)
      if (results.isEmpty) {
        logger.info("(empty)")
      } else {
        results.foreach(row => logger.info(row.toString))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to query archived_rates_providers: ${e.getMessage}", e)
    }
    logger.info("--- End Archived Rates Providers Table ---")

    addDatabaseCoordinatedShutdownTask(databaseManager)
    logger.info("Database setup completed")

    databaseManager
  }

  def setupHttpServer(databaseManager: DatabaseManager)(implicit system: ActorSystem): Unit = {
    val http = Http()
    val routes = setupRoutes(databaseManager, system)
    httpServerBinding = startHttpServer(http, bindInterface, bindPort, routes)(system)
  }

  private def setupRoutes(databaseManager: DatabaseManager, system: ActorSystem): Route = {

    implicit val executionContext: ExecutionContextExecutor = ioExecutionContext
    implicit val actorSystem: ActorSystem = system.classicSystem

    implicit val serverSettings: AkkaHttpServerOptions = AkkaHttpServerOptions.default

    val healthCheckManager = HealthCheckManager(Instant.now, databaseManager)
    val healthCheckRoute = HealthCheckRoute(healthCheckManager)

    // Setup HttpClient for RateProvider
    val httpClient = com.evidentid.http.client.HttpClient(system.classicSystem) // Use HttpClient's companion apply method

    val rateProvider = com.evidentid.application.upstream.RateProvider(httpClient)
    val ratesProviderManager = RatesProviderManager(databaseManager, rateProvider)
    val ratesProviderRoute = RatesProviderRoute(ratesProviderManager)

    val docsRoute = DocsRoute(healthCheckRoute.endpoints, ratesProviderRoute.endpoints)

    Route.seal(Directives.concat(docsRoute.route, healthCheckRoute.route, ratesProviderRoute.route))
  }

  def currentBinding: Future[InetSocketAddress] = httpServerBinding

}

object RunnableApplication {

  final val UtcClock = Clock.systemUTC()

  def apply(config: Config) = new RunnableApplication(
    config = config,
    actorSystemName = config.getString("application.actor-system-name"),
    bindInterface = config.getString("application.bind-interface"),
    bindPort = config.getInt("application.bind-port"),
    databaseConfig = DatabaseManager.DatabaseConnectionConfig(
      jdbcUrl = config.getString("database.jdbc-url"),
      numThreads = Some(config.getInt("database.connection-pool.number-of-threads")),
      queueSize = Some(config.getInt("database.connection-pool.queue-size")),
    ),
  )

}
