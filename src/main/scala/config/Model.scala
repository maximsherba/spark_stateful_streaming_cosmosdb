package config

import java.sql.Timestamp

object Model {
  case class EventRecord(sentAt: Timestamp, event: String, messageId: String, noField: Option[Long]) extends Serializable
  case class EventState(event: String, count: BigInt) extends Serializable

}
