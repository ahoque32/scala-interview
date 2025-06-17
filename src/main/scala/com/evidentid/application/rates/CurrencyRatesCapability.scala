package com.evidentid.application.rates

import com.evidentid.database.DatabaseCapability
import com.evidentid.database.model.Tables

import scala.concurrent.Future

trait CurrencyRatesCapability extends DatabaseCapability {

  import com.evidentid.database.DatabaseProfile.api._

    def insertRates(rates: Seq[Tables.CurrencyRate]): Future[Int] = {
    val insertAction = (Tables.CurrencyRates ++= rates).map(_.getOrElse(0))
    database.run(insertAction)
  }

}
