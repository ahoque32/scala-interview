package com.evidentid.http.server.api

import akka.http.scaladsl.server.Route
import com.evidentid.http.server.EndpointRoute
import com.evidentid.http.server.EndpointRoute.RouteBinding
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.akkahttp.{AkkaHttpServerInterpreter, AkkaHttpServerOptions}
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import scala.concurrent.{ExecutionContextExecutor, Future}

class DocsRoute(endpointsToIncludeInDocs: Seq[AnyEndpoint])(
    implicit val serverSettings: AkkaHttpServerOptions,
    val executionContextExecutor: ExecutionContextExecutor
) extends EndpointRoute {

  override val routeBindings: Seq[RouteBinding[_, _, _]] = Seq.empty

  private val swaggerUIRoutes: Route = {
    val swaggerEndpoints =
      SwaggerInterpreter()
        .fromEndpoints[Future](endpointsToIncludeInDocs.toList, "EID Scala app", "unknown")
    AkkaHttpServerInterpreter().toRoute(swaggerEndpoints)
  }

  override def route: Route = swaggerUIRoutes
}

object DocsRoute {

  def apply(
      endpointsToIncludeInDocs: Seq[AnyEndpoint]*
  )(implicit serverSettings: AkkaHttpServerOptions, executionContextExecutor: ExecutionContextExecutor): DocsRoute = {
    new DocsRoute(endpointsToIncludeInDocs.flatten)
  }

}
