package com.evidentid.application.rates

import com.evidentid.application.rates.api.Rate
import com.evidentid.application.upstream.RateProvider
import com.evidentid.application.upstream.RateProvider.UpstreamRateResponse
import com.evidentid.database.DatabaseManager
import com.evidentid.database.model.Tables
import com.evidentid.logging.Logging
import akka.http.scaladsl.model.DateTime

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal

class RatesProviderManager(database: DatabaseManager, rateProvider: RateProvider)(implicit ec: ExecutionContext) extends Logging {

  def getRates(currencyCode: String): Future[Seq[Rate]] = {
    database.getFirstRateProvider(currencyCode.toUpperCase).flatMap {
      case Some(provider) =>
        logger.info(s"Found provider '${provider.providerName}' (ID: ${provider.id}) for $currencyCode from DB.")
        rateProvider.getRate(provider.url, currencyCode, provider.id).flatMap { upstreamRates =>
          if (upstreamRates.nonEmpty) {
            val now = DateTime.now
            val newRateRows = upstreamRates.map { rate =>
              Tables.CurrencyRate(
                id = UUID.randomUUID(),
                providerId = rate.providerId,
                baseCurrency = rate.fromCurrency,
                targetCurrency = rate.toCurrency,
                rate = BigDecimal(rate.rate.toString),
                apiLastUpdatedAt = rate.date,
                fetchedAt = now
              )
            }

            database.insertRates(newRateRows).map { numInserted =>
              logger.info(s"Successfully inserted $numInserted rates into the database for $currencyCode.")
              upstreamRates.map(toApiRate)
            }.recover { case ex: Throwable =>
              logger.error(s"Failed to insert rates for $currencyCode into the database.", ex)
              upstreamRates.map(toApiRate)
            }
          } else {
            logger.warn(s"No rates received from RateProvider for $currencyCode and provider '${provider.providerName}'.")
            Future.successful(Seq.empty[Rate])
          }
        }

      case None =>
        logger.warn(s"No active provider found for currency code: $currencyCode")
        Future.successful(Seq.empty[Rate])
    }
  }

  private def toApiRate(upstream: UpstreamRateResponse): Rate = {
    Rate(
      fromCurrency = upstream.fromCurrency,
      toCurrency = upstream.toCurrency,
      rate = upstream.rate,
      date = upstream.date,
      rateProviderId = upstream.providerId
    )
  }

  // currencyToUrl is no longer directly used here as provider.url from DB is used.
  // private def currencyToUrl(currencyCode: String): String = s"http://example.com/$currencyCode"
}

object RatesProviderManager {
  def apply(database: DatabaseManager, rateProvider: RateProvider)(implicit
      executionContext: ExecutionContext
  ): RatesProviderManager = {
    new RatesProviderManager(database, rateProvider)
  }
}
