package com.evidentid.application.rates

// Removed: import akka.http.scaladsl.model.DateTime -- type inferred from Rate/UpstreamRateResponse
import com.evidentid.application.rates.api.Rate
import com.evidentid.application.upstream.RateProvider
import com.evidentid.application.upstream.RateProvider.UpstreamRateResponse
import com.evidentid.database.DatabaseManager
import com.evidentid.logging.Logging

// Removed: import java.util.UUID -- type inferred from Rate/UpstreamRateResponse
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

@nowarn
class RatesProviderManager(database: DatabaseManager, rateProvider: RateProvider)(implicit executionContext: ExecutionContext)
    extends Logging {

  def getRates(currencyCode: String): Future[Seq[Rate]] = {
    // TODO: The URL is still hardcoded in currencyToUrl, RateProvider will need to use it or be configured.
    val url = currencyToUrl(currencyCode) // This URL isn't actually used by RateProvider.getRate's current mock
    rateProvider.getRate(url, currencyCode).map(upstreamRates => upstreamRates.map(toApiRate))
  }

  private def toApiRate(upstream: UpstreamRateResponse): Rate = {
    Rate(
      fromCurrency = upstream.fromCurrency,
      toCurrency = upstream.toCurrency,
      rate = upstream.rate,
      date = upstream.date,
      rateProviderId = upstream.providerId // Field name mapping
    )
  }

  // hardcoded URL for 1st iteration - will be used by RateProvider later
  private def currencyToUrl(currencyCode: String): String = s"http://example.com/$currencyCode"
}

object RatesProviderManager {

  def apply(database: DatabaseManager, rateProvider: RateProvider)(implicit
      executionContext: ExecutionContext
  ): RatesProviderManager = {
    new RatesProviderManager(database, rateProvider)
  }

}
