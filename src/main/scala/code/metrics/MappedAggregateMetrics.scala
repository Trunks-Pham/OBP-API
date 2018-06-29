package code.metrics

import java.util.Date
import net.liftweb.mapper._

object MappedAggregateMetrics extends AggregateMetrics {
  override def getAllAggregateMetrics(startDate: Date, endDate: Date): List[Double] = {

    val dbQuery = s"SELECT count(*), avg(duration), min(duration), max(duration) FROM mappedmetric WHERE date_c >= ? AND date_c <= ?"
    /**
      * Example of a Tuple response
      * (List(count, avg, min, max),List(List(7503, 70.3398640543782487, 0, 9039)))
      * First value of the Tuple is a List of field names returned by SQL query.
      * Second value of the Tuple is a List of rows of the result returned by SQL query. Please note it's only one row.
      */
    val (_, List(count :: avg :: min :: max :: _)) = DB.use(DefaultConnectionIdentifier)
    {
      conn =>
          DB.prepareStatement(dbQuery, conn)
          {
            stmt =>
              stmt.setDate(1, new java.sql.Date(startDate.getTime))
              stmt.setDate(2, new java.sql.Date(endDate.getTime))
              DB.resultSetTo(stmt.executeQuery())
              
          }
    }
    

    val totalCount = count
    val avgResponseTime = "%.2f".format(avg.toDouble)
    val minResponseTime = min
    val maxResponseTime = max


    List(totalCount.toDouble, avgResponseTime.toDouble, minResponseTime.toDouble, maxResponseTime.toDouble)
  }

}