package code.counterpartylimit

import code.api.util.APIUtil
import net.liftweb.util.SimpleInjector
import net.liftweb.common.Box
import scala.concurrent.Future

object CounterpartyLimitProvider extends SimpleInjector {
  val rateLimiting = new Inject(buildOne _) {}
  def buildOne: CounterpartyLimitProviderTrait = APIUtil.getPropsAsBoolValue("use_akka", false) match {
    case _  => MappedCounterpartyLimitProvider
//    case true => RemotedataCounterpartyLimit // we are getting rid of the akka now. so do not implement it here
  }
}

trait CounterpartyLimitProviderTrait {
  def getAll(): Future[List[CounterpartyLimit]]
  def getByCounterpartyLimitId(counterpartyLimitId: String): Future[Box[CounterpartyLimit]]
  def deleteByCounterpartyLimitId(counterpartyLimitId: String): Future[Box[Boolean]]
  def createOrUpdateCounterpartyLimit(
    bankId: String,
    accountId: String,
    viewId: String,
    counterpartyId: String,
    maxSingleAmount: Int,
    maxMonthlyAmount: Int,
    maxNumberOfMonthlyTransactions: Int,
    maxYearlyAmount: Int,
    maxNumberOfYearlyTransactions: Int): Future[Box[CounterpartyLimit]]
}

trait CounterpartyLimitTrait {
  def counterpartyLimitId: String
  def bankId: String
  def accountId: String
  def viewId: String
  def counterpartyId: String
  
  def maxSingleAmount: Int
  def maxMonthlyAmount: Int
  def maxNumberOfMonthlyTransactions: Int
  def maxYearlyAmount: Int
  def maxNumberOfYearlyTransactions: Int
}

