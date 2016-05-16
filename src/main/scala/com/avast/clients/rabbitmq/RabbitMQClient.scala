package com.avast.clients.rabbitmq

import java.time.{Clock, Duration}
import java.util.UUID

import com.avast.clients.rabbitmq.RabbitMQClientFactory.ServerChannel
import com.avast.clients.rabbitmq.api.RabbitMQSenderAndReceiver
import com.avast.metrics.api.Monitor
import com.avast.utils2.errorhandling.FutureTimeouter._
import com.rabbitmq.client._
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class RabbitMQClient private[rabbitmq](name: String,
                                       channel: ServerChannel,
                                       processTimeout: Duration,
                                       clock: Clock,
                                       monitor: Monitor,
                                       receiverConfig: Option[(ReceiverConfig, Delivery => Future[Boolean])],
                                       sendAction: Option[Delivery => Unit])
                                      (implicit ec: ExecutionContext) extends RabbitMQSenderAndReceiver with StrictLogging {


  channel.addRecoveryListener(ChannelRecoveryListener)
  channel.addShutdownListener(ChannelShutdownListener)

  receiverConfig.foreach { case (config, action) =>
    import config._

    val consumer = new RabbitMQConsumer(name, channel, clock, monitor)({ delivery =>
      try {
        action(delivery)
          .timeoutAfter(processTimeout)
          .recover {
            case NonFatal(e) =>
              logger.warn("Error while executing callback, automatically requeuing", e)
              false
          }
      } catch {
        case NonFatal(e) =>
          logger.error("Error while executing callback, automatically requeuing", e)
          Future.successful(false)
      }
    })

    channel.basicConsume(queueName, false, consumer)
  }

  protected def send(delivery: Delivery): Unit = {
    val action = sendAction.getOrElse(throw new IllegalStateException("This client is not configured as sender"))

    try {
      action(delivery)
    } catch {
      case NonFatal(e) => logger.error("Error while sending message", e)
    }
  }

  override def send(routingKey: String, body: Array[Byte], properties: AMQP.BasicProperties): Unit = {
    send(Delivery(body, properties, routingKey))
  }

  override def send(routingKey: String, body: Array[Byte]): Unit = {
    val properties = new AMQP.BasicProperties.Builder()
      .messageId(UUID.randomUUID().toString)
      .build()

    send(routingKey, body, properties)
  }

  override def close(): Unit = {
    channel.close()
  }

  private object ChannelShutdownListener extends ShutdownListener with LazyLogging {
    override def shutdownCompleted(cause: ShutdownSignalException): Unit = {
      if (!cause.isInitiatedByApplication) {
        logger.info(s"Channel of $name was closed, waiting for recover", cause)
      }
      else {
        logger.debug(s"Channel of $name was closed")
      }
    }
  }

  private object ChannelRecoveryListener extends RecoveryListener with LazyLogging {
    override def handleRecovery(recoverable: Recoverable): Unit = {
      logger.info(s"Channel of $name was recovered")
    }
  }

}


case class Delivery(body: Array[Byte], properties: AMQP.BasicProperties, routingKey: String)