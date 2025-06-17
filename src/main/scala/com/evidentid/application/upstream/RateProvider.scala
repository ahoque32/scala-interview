package com.evidentid.application.upstream

import akka.http.scaladsl.model.DateTime
import com.evidentid.http.client.HttpClient
import com.evidentid.http.server.formats.DateTimeJsonFormat
import com.typesafe.config.Config
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import sttp.model.Uri
import sttp.tapir.Schema
import com.evidentid.logging.Logging

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class RateProvider(httpClient: HttpClient, config: Config)(implicit ec: ExecutionContext) extends Logging {
  import RateProvider._ // Companion object imports

  private val apiKey: String = config.getString("exchangerate-api.api-key")
  private val baseUrl: String = config.getString("exchangerate-api.base-url")
  private val apiBaseCurrency: String = config.getString("exchangerate-api.base-currency")

  def getRate(_ignoredUrl: String, requestedCurrencyCode: String, actualProviderId: java.util.UUID): Future[Seq[UpstreamRateResponse]] = {
    val _ = _ignoredUrl // Explicitly mark as unused
    if (apiKey == "YOUR_API_KEY_HERE") {
      logger.warn("API key for ExchangeRate-API is a placeholder. Real API calls will fail.")
    }

    val fullApiUrl = s"${baseUrl}${apiKey}/latest/${apiBaseCurrency}"
    logger.info(s"Fetching rates from: $fullApiUrl for base $apiBaseCurrency, target $requestedCurrencyCode")

    Uri.parse(fullApiUrl) match {
      case Left(parseError) =>
        logger.error(s"Failed to parse API URL '$fullApiUrl': $parseError")
        Future.successful(Seq.empty)
      case Right(uri) =>
        httpClient.get[ExchangeRateApiResponse](uri)
          .map { response =>
            if (response.body.result == "success") {
              response.body.conversion_rates.get(requestedCurrencyCode.toUpperCase) match {
                case Some(rateValue) =>
                  val apiUpdateTime = Try(ZonedDateTime.parse(response.body.time_last_update_utc, DateTimeFormatter.RFC_1123_DATE_TIME)) match {
                    case Success(zdt) => DateTime(zdt.toInstant.toEpochMilli)
                    case Failure(ex) =>
                      logger.error(s"Failed to parse time_last_update_utc '${response.body.time_last_update_utc}': ${ex.getMessage}. Defaulting to DateTime.now.", ex)
                      DateTime.now // Fallback
                  }
                  Seq(UpstreamRateResponse(
                    fromCurrency = apiBaseCurrency,
                    toCurrency = requestedCurrencyCode.toUpperCase,
                    rate = rateValue,
                    date = apiUpdateTime, // Use parsed time from API
                    providerId = actualProviderId // Use actual provider ID from parameter
                  ))
                case None =>
                  logger.warn(s"Currency code '$requestedCurrencyCode' not found in API response from $fullApiUrl.")
                  Seq.empty
              }
            } else {
              logger.error(s"ExchangeRate-API request failed. Result: ${response.body.result}. URL: $fullApiUrl")
              Seq.empty
            }
          }
          .recover {
            case e: Exception =>
              logger.error(s"Exception during HTTP request to $fullApiUrl: ${e.getMessage}", e)
              Seq.empty
          }
    }
  }
}

object RateProvider extends DateTimeJsonFormat {
  // Response structure from ExchangeRate-API (v6)
  case class ExchangeRateApiResponse(
    result: String,
    documentation: String,
    terms_of_use: String,
    time_last_update_unix: Long,
    time_last_update_utc: String,
    time_next_update_unix: Long,
    time_next_update_utc: String,
    base_code: String, // e.g. "USD"
    conversion_rates: Map[String, Float] // e.g. {"USD": 1.0, "EUR": 0.92, ...}
  )

  object ExchangeRateApiResponse {
    implicit val codec: Codec[ExchangeRateApiResponse] = deriveCodec[ExchangeRateApiResponse]
    // No Tapir schema needed here as this is for client-side parsing, not API exposure
  }

  // This is the structure expected by RatesProviderManager
  final case class UpstreamRateResponse(fromCurrency: String, toCurrency: String, rate: Float, date: DateTime, providerId: UUID)

  object UpstreamRateResponse {
    implicit val codecForUpstreamRateResponse: Codec[UpstreamRateResponse] = deriveCodec[UpstreamRateResponse]
    implicit lazy val schemaForUpstreamRateResponse: Schema[UpstreamRateResponse] = Schema.derived[UpstreamRateResponse]
  }

  // Removed: CurrenciesWithAvailableRates (no longer needed for mock data)

  def apply(httpClient: HttpClient, config: Config)(implicit ec: ExecutionContext): RateProvider = 
    new RateProvider(httpClient, config)
}
