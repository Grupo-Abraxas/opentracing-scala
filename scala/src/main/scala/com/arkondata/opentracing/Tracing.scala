package com.arkondata.opentracing

import scala.language.implicitConversions
import scala.util.Try

import cats.arrow.FunctionK
import cats.{ Defer, MonadError, ~> }
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import com.arkondata.opentracing.util.cats.defer
import io.opentracing.{ Scope, Span, SpanContext, Tracer }

trait Tracing[F0[_], F1[_]] {
  parent =>

  import Tracing._

  protected def build(spanBuilder: Tracer.SpanBuilder, activate: Boolean): F0 ~> F1
  protected def noTrace: F0 ~> F1

  class InterfaceImpl[Out](mkOut: (F0 ~> F1) => Out)(implicit val tracer: Tracer) extends Interface[Out] {
    def apply(parent: Option[Either[Span, SpanContext]], activate: Boolean, operation: String, tags: Map[String, TagValue]): Out =
      Tracing.Interface.impl(parent, activate, operation, tags)(tracer, (b, a) => mkOut(build(b, a)), mkOut(noTrace))
  }

  def transform(implicit tracer: Tracer): Transform = new Transform
  class Transform(implicit tracer: Tracer) extends InterfaceImpl[F0 ~> F1](locally)

  def apply[A](fa: => F0[A])(implicit tracer: Tracer): PartiallyApplied[A] = new PartiallyApplied(fa)
  class PartiallyApplied[A](fa: F0[A])(implicit tracer: Tracer) extends InterfaceImpl[F1[A]](_(fa))


  def map[R[_]](f: F1 ~> R): Tracing[F0, R] =
    new Tracing[F0, R] {
      protected def build(spanBuilder: Tracer.SpanBuilder, activate: Boolean): F0 ~> R =
        f compose parent.build(spanBuilder, activate)
      protected def noTrace: F0 ~> R = f compose parent.noTrace
    }
  def contramap[S[_]](f: S ~> F0): Tracing[S, F1] =
    new Tracing[S, F1] {
      protected def build(spanBuilder: Tracer.SpanBuilder, activate: Boolean): S ~> F1 =
        parent.build(spanBuilder, activate) compose f
      protected def noTrace: S ~> F1 = parent.noTrace compose f
    }
}

object Tracing extends TracingEvalLaterImplicits {
  type Endo[F[_]] = Tracing[F, F]

  /** Common interface for building traces. Starts active by default. */
  trait Interface[Out] {
    def apply(operation: String, tags: Tag*): Out                                         = apply(parent = None, activate = true, operation, buildTags(tags))
    def apply(activate: Boolean, operation: String, tags: Tag*): Out                      = apply(parent = None, activate, operation, buildTags(tags))
    def apply(parent: Span, operation: String, tags: Tag*): Out                           = apply(Some(Left(parent)), activate = true, operation, buildTags(tags))
    def apply(parent: Span, activate: Boolean, operation: String, tags: Tag*): Out        = apply(Some(Left(parent)), activate, operation, buildTags(tags))
    def apply(parent: SpanContext, operation: String, tags: Tag*): Out                    = apply(Some(Right(parent)), activate = true, operation, buildTags(tags))
    def apply(parent: SpanContext, activate: Boolean, operation: String, tags: Tag*): Out = apply(Some(Right(parent)), activate, operation, buildTags(tags))
    private def buildTags(tags: Seq[Tag]): Map[String, TagValue] = tags.map(_.toPair).toMap

    def map[R](f: Out => R): Interface[R] = Interface.Mapped(this, f)

    val tracer: Tracer
    def apply(parent: Option[Either[Span, SpanContext]], activate: Boolean, operation: String, tags: Map[String, TagValue]): Out
  }
  object Interface {
    case class Mapped[T, R](original: Interface[T], f: T => R) extends Interface[R] {
      val tracer: Tracer = original.tracer
      def apply(parent: Option[Either[Span, SpanContext]], activate: Boolean, operation: String, tags: Map[String, TagValue]): R =
        f(original(parent, activate, operation, tags))
    }

    type Activate = Boolean
    def impl[Out](parent: Option[Either[Span, SpanContext]], activate: Activate, operation: String, tags: Map[String, TagValue])
                 (tracer: Tracer, build: (Tracer.SpanBuilder, Activate) => Out, noTrace: => Out): Out =
      if (tracer eq null) noTrace
      else {
        val builder0 = tags.foldLeft(tracer.buildSpan(operation)) {
          case (acc, (key, TagValue.String(str))) => acc.withTag(key, str)
          case (acc, (key, TagValue.Number(num))) => acc.withTag(key, num)
          case (acc, (key, TagValue.Boolean(b)))  => acc.withTag(key, b)
        }
        val builder1 = parent.map(_.fold(builder0.asChildOf, builder0.asChildOf)).getOrElse(builder0)
        build(builder1, activate)
      }
  }


  final case class Tag(key: String, value: TagValue) {
    def toPair: (String, TagValue) = key -> value
  }
  object Tag {
    implicit def tagValueToTag(pair: (String, TagValue)): Tag = Tag(pair._1, pair._2)
    implicit def valueToTag[V: TagValue.Lift](pair: (String, V)): Tag = Tag(pair._1, TagValue.Lift(pair._2))
  }

  sealed trait TagValue
  object TagValue {

    sealed trait Lift[A] extends (A => TagValue)
    object Lift {
      def apply[A](a: A)(implicit lift: Lift[A]): TagValue = lift(a)

      implicit def liftString: Lift[java.lang.String] = define(String.apply)
      implicit def liftJavaNumber[N <: java.lang.Number]: Lift[N] = define(Number.apply)
      implicit def liftInt: Lift[Int]       = define(Number.apply _ compose Int.box)
      implicit def liftLong: Lift[Long]     = define(Number.apply _ compose Long.box)
      implicit def liftDouble: Lift[Double] = define(Number.apply _ compose Double.box)
      implicit def liftFloat: Lift[Float]   = define(Number.apply _ compose Float.box)
      implicit def liftBoolean: Lift[scala.Boolean] = define(Boolean.apply)

      private def define[A](f: A => TagValue) = new Lift[A] { def apply(v1: A): TagValue = f(v1) }
    }

    implicit def apply[A: Lift](a: A): TagValue = Lift(a)

    case class String (get: java.lang.String) extends TagValue { type Value = java.lang.String }
    case class Number (get: java.lang.Number) extends TagValue { type Value = java.lang.Number }
    case class Boolean(get: scala.Boolean)    extends TagValue { type Value = scala.Boolean }
  }

  class TracingSetup(
    val beforeStart: Tracer.SpanBuilder => Tracer.SpanBuilder = locally,
    val justAfterStart: Span => Unit                          = _ => {},
    val beforeStop: Either[Throwable, _] => Span => Unit      = _ => _ => {}
  )
  object TracingSetup {
    object Dummy {
      implicit object DummyTracingSetup extends TracingSetup()
    }
  }


  implicit def tracingDeferMonadError[F[_]](implicit setup: TracingSetup, D: Defer[F], M: MonadError[F, Throwable], t: Tracer): Tracing[F, F] =
    new Tracing[F, F] {
      protected def build(spanBuilder: Tracer.SpanBuilder, activate: Boolean): F ~> F = {
        val builder = setup.beforeStart(spanBuilder)
        λ[F ~> F] { fa =>
          for {
            span <- defer[F]{ builder.start() }
            scope <- defer[F]{ if (activate) Some(t.activateSpan(span)) else None }
            _ <- M.pure(Try { setup.justAfterStart(span) })
            attempt <- fa.attempt
            _ <- M.pure { closeScopeAndSpan(span, scope, setup.beforeStop(attempt)) }
            result <- M.pure(attempt).rethrow
          } yield result
        }
      }
      private def closeScopeAndSpan(span: Span, scope: Option[Scope], before: Span => Unit): Unit =
        try before(span)
        finally {
          util.finishSpanSafe(span)
          scope.foreach(util.closeScopeSafe)
        }

      protected def noTrace: F ~> F = FunctionK.id[F]
    }
}
