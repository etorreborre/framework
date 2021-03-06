/*
 * Copyright 2009-2011 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb
package actor

import common._

trait ILAExecute {
  def execute(f: () => Unit): Unit
  def shutdown(): Unit
}

object LAScheduler extends Loggable {
  @volatile
  var onSameThread = false

  /**
   * Set this variable to the number of threads to allocate in the thread pool
   */
  @volatile var threadPoolSize = 16 // issue 194

  @volatile var maxThreadPoolSize = threadPoolSize * 25

  @volatile
  var createExecutor: () => ILAExecute = () => {
    new ILAExecute {
      import java.util.concurrent._

      private val es = // Executors.newFixedThreadPool(threadPoolSize)
        new ThreadPoolExecutor(threadPoolSize, 
                               maxThreadPoolSize,
                               60,
                               TimeUnit.SECONDS,
                               new LinkedBlockingQueue)

      def execute(f: () => Unit): Unit =
      es.execute(new Runnable{def run() {
        try {
          f()
        } catch {
          case e: Exception => logger.error("Lift Actor Scheduler", e)
        }
      }})

      def shutdown(): Unit = {
        es.shutdown()
      }
    }
  }

  @volatile
  var exec: ILAExecute = _

  def execute(f: () => Unit) {
    synchronized {
      if (exec eq null) {
        exec = createExecutor()
      }
      exec.execute(f)
    }
  }

  def shutdown() {
    synchronized {
      if (exec ne null) {
        exec.shutdown()
      }
      
      exec = null
    }
  }
}

trait SpecializedLiftActor[T] extends SimpleActor[T]  {
  @volatile private[this] var processing = false
  private[this] val baseMailbox: MailboxItem = new SpecialMailbox
  @volatile private[this] var msgList: List[T] = Nil
  @volatile private[this] var priorityMsgList: List[T] = Nil
  @volatile private[this] var startCnt = 0

  private class MailboxItem(val item: T) {
    var next: MailboxItem = _
    var prev: MailboxItem = _

    /*
    def find(f: MailboxItem => Boolean): Box[MailboxItem] =
    if (f(this)) Full(this) else next.find(f)
    */

    def remove() {
      val newPrev = prev
      prev.next = next
      next.prev = prev
    }

    def insertAfter(newItem: MailboxItem): MailboxItem = {
      next.prev = newItem
      newItem.prev = this
      newItem.next = this.next
      next = newItem
      newItem
    }

    def insertBefore(newItem: MailboxItem): MailboxItem = {
      prev.next = newItem
      newItem.prev = this.prev
      newItem.next = this
      prev = newItem
      newItem
    }
  }

  private class SpecialMailbox extends MailboxItem(null.asInstanceOf[T]) {
    // override def find(f: MailboxItem => Boolean): Box[MailboxItem] = Empty
    next = this
    prev = this
  }

  private def findMailboxItem(start: MailboxItem, f: MailboxItem => Boolean): Box[MailboxItem] =
    start match {
      case x: SpecialMailbox => Empty
      case x if f(x) => Full(x)
      case x => findMailboxItem(x.next, f)
    }
  
  /**
   * Send a message to the Actor.  This call will always succeed
   * and return almost immediately.  The message will be processed
   * asynchronously.  This is a Java-callable alias for !.
   */
  def send(msg: T): Unit = this ! msg

  /**
   * Send a message to the Actor.  This call will always succeed
   * and return almost immediately.  The message will be processed
   * asynchronously.
   */
  def !(msg: T): Unit = {
    val toDo: () => Unit = baseMailbox.synchronized {
      msgList ::= msg
      if (!processing) {
        if (LAScheduler.onSameThread) {
          processing = true
          () => processMailbox(true)
        } else {
          if (startCnt == 0) {
            startCnt += 1
            () => LAScheduler.execute(() => processMailbox(false))
          } else
          () => {}
        }
      }
      else () => {}
    }
    toDo()
  }

  /**
   * This method inserts the message at the head of the mailbox
   * It's protected because this functionality may or may not want
   * to be exposed'
   */
  protected def insertMsgAtHeadOfQueue_!(msg: T): Unit = {
     val toDo: () => Unit = baseMailbox.synchronized {
      this.priorityMsgList ::= msg
      if (!processing) {
        if (LAScheduler.onSameThread) {
          processing = true
          () => processMailbox(true)
        } else {
          if (startCnt == 0) {
            startCnt += 1
            () => LAScheduler.execute(() => processMailbox(false))
          } else
          () => {}
        }
      }
      else () => {}
    }
    toDo()
  }

  private def processMailbox(ignoreProcessing: Boolean) {
    around {
      proc2(ignoreProcessing)
    }
  }

/**
 * A list of LoanWrappers that will be executed around the evaluation of mailboxes
 */
protected def aroundLoans: List[CommonLoanWrapper] = Nil

/**
 * You can wrap calls around the evaluation of the mailbox.  This allows you to set up
 * the environment
 */
protected def around[R](f: => R): R = aroundLoans match {
  case Nil => f
  case xs => CommonLoanWrapper(xs)(f)
}

  private def proc2(ignoreProcessing: Boolean) {
    var clearProcessing = true
    baseMailbox.synchronized {
      if (!ignoreProcessing && processing) return
      processing = true
      if (startCnt > 0) startCnt = 0
    }

    val eh = exceptionHandler

    def putListIntoMB(): Unit = {
      if (!priorityMsgList.isEmpty) {
      priorityMsgList.foldRight(baseMailbox)((msg, mb) => mb.insertAfter(new MailboxItem(msg)))
      priorityMsgList = Nil
      }

      if (!msgList.isEmpty) {
      msgList.foldLeft(baseMailbox)((mb, msg) => mb.insertBefore(new MailboxItem(msg)))
      msgList = Nil
      }
    }

    try {
      while (true) {
        baseMailbox.synchronized {
          putListIntoMB()
        }

            var keepOnDoingHighPriory = true

            while (keepOnDoingHighPriory) {
              val hiPriPfBox = highPriorityReceive
              if (hiPriPfBox.isDefined) {
                val hiPriPf = hiPriPfBox.open_!
                findMailboxItem(baseMailbox.next, mb => testTranslate(hiPriPf.isDefinedAt)(mb.item)) match {
                  case Full(mb) =>
                    mb.remove()
                    try {
                      execTranslate(hiPriPf)(mb.item)
                    } catch {
                      case e: Exception => if (eh.isDefinedAt(e)) eh(e)
                    }
                  case _ =>
                    baseMailbox.synchronized {
                      if (msgList.isEmpty) {
                        keepOnDoingHighPriory = false
                      }
                      else {
                        putListIntoMB()
                      }
                    }
                } }
              else {keepOnDoingHighPriory = false}
            }

            val pf = messageHandler

        findMailboxItem(baseMailbox.next, mb => testTranslate(pf.isDefinedAt)(mb.item)) match {
          case Full(mb) =>
            mb.remove()
            try {
              execTranslate(pf)(mb.item)
            } catch {
              case e: Exception => if (eh.isDefinedAt(e)) eh(e)
            }
          case _ =>
            baseMailbox.synchronized {
              if (msgList.isEmpty) {
                processing = false
                clearProcessing = false
                return
              }
              else {
                putListIntoMB()
              }
            }
        }
      }
    } catch {
      case e =>
        if (eh.isDefinedAt(e)) eh(e)
        throw e
    } finally {
      if (clearProcessing) {
        baseMailbox.synchronized {
          processing = false
        }
      }
    }
  }

  protected def testTranslate(f: T => Boolean)(v: T): Boolean = f(v)

  protected def execTranslate(f: T => Unit)(v: T): Unit = f(v)

  protected def messageHandler: PartialFunction[T, Unit]

  protected def highPriorityReceive: Box[PartialFunction[T, Unit]] = Empty

  protected def exceptionHandler: PartialFunction[Throwable, Unit] = {
    case e => ActorLogger.error("Actor threw an exception", e)
  }
}

object ActorLogger extends Logger {
}

private final case class MsgWithResp(msg: Any, future: LAFuture[Any])

trait LiftActor extends SpecializedLiftActor[Any]
with GenericActor[Any]
with ForwardableActor[Any, Any] {
  @volatile
  private[this] var responseFuture: LAFuture[Any] = null



  protected final def forwardMessageTo(msg: Any, forwardTo: TypedActor[Any, Any]) {
    if (null ne responseFuture) {
      forwardTo match {
        case la: LiftActor => la ! MsgWithResp(msg, responseFuture)
        case other =>
          reply(other !? msg)
      }
    } else forwardTo ! msg
  }

  /**
  * Send a message to the Actor and get an LAFuture
  * that will contain the reply (if any) from the message.
  * This method calls !&lt; and is here for Java compatibility
  */
  def sendAndGetFuture(msg: Any): LAFuture[Any] = this !< msg

  /**
  * Send a message to the Actor and get an LAFuture
  * that will contain the reply (if any) from the message
  */
  def !<(msg: Any): LAFuture[Any] = {
    val future = new LAFuture[Any]
    this ! MsgWithResp(msg, future)
    future
  }

  /**
  * Send a message to the Actor and wait for
  * the actor to process the message and reply.
  * This method is the Java callable version of !?.
  */
  def sendAndGetReply(msg: Any): Any = this !? msg
  
  /**
  * Send a message to the Actor and wait for
  * the actor to process the message and reply.
  */
  def !?(msg: Any): Any = {
    val future = new LAFuture[Any]
    this ! MsgWithResp(msg, future)
    future.get
  }


  /**
  * Send a message to the Actor and wait for
  * up to timeout milliseconds for
  * the actor to process the message and reply.
  * This method is the Java callable version of !?.
  */
  def sendAndGetReply(timeout: Long, msg: Any): Any = this.!?(timeout, msg)

  /**
  * Send a message to the Actor and wait for
  * up to timeout milliseconds for
  * the actor to process the message and reply.
  */
  def !?(timeout: Long, message: Any): Box[Any] =
    this !! (message, timeout)


    /**
    * Send a message to the Actor and wait for
    * up to timeout milliseconds for
    * the actor to process the message and reply.
    */
  def !!(msg: Any, timeout: Long): Box[Any] = {
    val future = new LAFuture[Any]
    this ! MsgWithResp(msg, future)
    future.get(timeout)
  }

  /**
  * Send a message to the Actor and wait for
  * the actor to process the message and reply.
  */
  def !!(msg: Any): Box[Any] = {
    val future = new LAFuture[Any]
    this ! MsgWithResp(msg, future)
    Full(future.get)
  }

  override protected def testTranslate(f: Any => Boolean)(v: Any) = v match {
    case MsgWithResp(msg, _) => f(msg)
    case v => f(v)
  }

  override protected def execTranslate(f: Any => Unit)(v: Any) = v match {
    case MsgWithResp(msg, future) =>
      responseFuture = future
      try {
        f(msg)
      } finally {
        responseFuture = null
      }
    case v => f(v)
  }

  /**
  * The Actor should call this method with a reply
  * to the message
  */
  protected def reply(v: Any) {
    if (null ne responseFuture) {
      responseFuture.satisfy(v)
    }
  }
}

import java.lang.reflect._

object LiftActorJ {
  private var methods: Map[Class[_], DispatchVendor] = Map()

  def calculateHandler(what: LiftActorJ): PartialFunction[Any, Unit] = 
    synchronized {
      val clz = what.getClass
      methods.get(clz) match {
        case Some(pf) => pf.vend(what)
        case _ => {
          val pf = buildPF(clz)
          methods += clz -> pf
          pf.vend(what)
        }
      }
    }

  private def getBaseClasses(clz: Class[_]): List[Class[_]] = clz match {
    case null => Nil
    case clz => clz :: getBaseClasses(clz.getSuperclass)
  }

  private def receiver(in: Method): Boolean = {
    in.getParameterTypes().length == 1 &&
    (in.getAnnotation(classOf[JavaActorBase.Receive]) != null)
  }

  private def buildPF(clz: Class[_]): DispatchVendor = {
    val methods = getBaseClasses(clz).
    flatMap(_.getDeclaredMethods.toList.filter(receiver))

    val clzMap: Map[Class[_], Method] = 
      Map(methods.map{m => 
        m.setAccessible(true) // access private and protected methods
        m.getParameterTypes().apply(0) -> m} :_*)

    new DispatchVendor(clzMap)
  }
}

private final class DispatchVendor(map: Map[Class[_], Method]) {
  private val baseMap: Map[Class[_], Option[Method]] = 
    Map(map.map{case (k,v) => (k, Some(v))}.toList :_*)

  def vend(actor: LiftActorJ): PartialFunction[Any, Unit] =
    new PartialFunction[Any, Unit] {
      var theMap: Map[Class[_], Option[Method]] = baseMap

      def findClass(clz: Class[_]): Option[Method] = 
        theMap.find(_._1.isAssignableFrom(clz)).flatMap(_._2)

      def isDefinedAt(v: Any): Boolean = {
        val clz = v.asInstanceOf[Object].getClass
        theMap.get(clz) match {
          case Some(Some(_)) => true
          case None => {
            val answer = findClass(clz)
            theMap += clz -> answer
            answer.isDefined
          }
          case _ => false
        }
      }

      def apply(v: Any): Unit = {
        val o: Object = v.asInstanceOf[Object]
        val meth = theMap(o.getClass).get
        meth.invoke(actor, o) match {
          case null => 
          case x => actor.internalReply(x)
        }
      }
    }
}

/**
 * Java versions of Actors should subclass this method.
 * Methods decorated with the @Receive annotation
 * will receive messages of that type.
 */
class LiftActorJ extends JavaActorBase with LiftActor {
  protected lazy val _messageHandler: PartialFunction[Any, Unit] =
    calculateJavaMessageHandler

  protected def calculateJavaMessageHandler = LiftActorJ.calculateHandler(this)

  protected def messageHandler = _messageHandler

  private[actor] def internalReply(v: Any) = reply(v)  
}
