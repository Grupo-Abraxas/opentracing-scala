package com.github.fehu.opentracing.v2

import scala.language.existentials

import cats.{ Applicative, Defer, Functor, Monad, ~> }
import cats.effect.Resource
import cats.syntax.flatMap._
import io.opentracing.{ SpanContext, Tracer }
import io.opentracing.propagation.Format

import com.github.fehu.opentracing.v2.internal.syntax.LowPrioritySyntax

package object syntax extends LowPrioritySyntax {

  final implicit class TracedOps[F[_], A](fa: F[A])(implicit traced: Traced[F]) {
    def trace(operation: String, tags: Traced.Tag*): F[A] = traced(operation, tags: _*)(fa)

    def inject(context: SpanContext)(operation: String, tags: Traced.Tag*): F[A] =
      traced.injectContext(context)(operation, tags: _*)(fa)

    def injectFrom[C](carrier: C, format: Format[C])(operation: String, tags: Traced.Tag*): F[A] =
      traced.injectContextFrom(carrier, format)(operation, tags: _*)(fa)
  }

  sealed trait TracedFunctions {
    def currentSpan[F[_]](implicit traced: Traced[F]): Traced.SpanInterface[F] = traced.currentSpan

    def extractContext[F[_]]: TracedFunctions.Extract[F] = TracedFunctions.extractInstance.asInstanceOf[TracedFunctions.Extract[F]]

    def mapK[T[_[*], *], F[_], G[_]: Functor](f: F ~> G)(implicit traced: Traced2[T, F]): T[F, *] ~> T[G, *] = traced.mapK(f)

    def trace[F[_]](operation: String, tags: Traced.Tag*): TracedFunctions.Trace[F] = new TracedFunctions.Trace(operation, tags)

    def traceK[F[_]](operation: String, tags: Traced.Tag*)(implicit traced: Traced[F]): F ~> F =
      λ[F ~> F](f => traced(operation, tags: _*)(f))

    def delay[F[_]]: TracedFunctions.Delay[F] = TracedFunctions.delayInstance.asInstanceOf[TracedFunctions.Delay[F]]
  }

  object TracedFunctions extends TracedFunctions {
    protected[syntax] class Extract[F[_]] {
      def apply[C0 <: C, C](carrier: C0, format: Format[C])(implicit traced: Traced[F]): F[Option[C0]] =
        traced.extractContext(carrier, format)
    }
    protected[syntax] class Trace[F[_]](operation: String, tags: Seq[Traced.Tag]) {
      def apply[A](a: => A)(implicit traced: Traced[F]): F[A] = traced(operation, tags: _*)(traced.defer(traced.pure(a)))
    }
    protected[syntax] class Delay[F[_]] {
      def apply[A](a: => A)(implicit traced: Traced[F]): F[A] = traced.defer(traced.pure(a))
    }
    protected lazy val extractInstance = new TracedFunctions.Extract[cats.Id]
    protected lazy val delayInstance = new TracedFunctions.Delay[cats.Id]
  }

  final implicit class TracedIdOps(obj: Traced.type) extends TracedFunctions

  final implicit class Traced2Ops[F[_[*], *], G[_], A](fa: F[G, A])(implicit traced: Traced2[F, G]) {
    def runTraced(params: Traced.RunParams): G[A] = traced.run(fa, params)

    def runTraced(tracer: Tracer, hooks: Traced.Hooks, parent: Traced.ActiveSpan): G[A] =
      runTraced(Traced.RunParams(tracer, hooks, parent))
    def runTraced(tracer: Tracer, hooks: Traced.Hooks): G[A] =
      runTraced(Traced.RunParams(tracer, hooks, Traced.ActiveSpan.empty))
    def runTraced(tracer: Tracer, parent: Traced.ActiveSpan): G[A] =
      runTraced(Traced.RunParams(tracer, Traced.Hooks(), parent))
    def runTraced(tracer: Tracer): G[A] =
      runTraced(Traced.RunParams(tracer, Traced.Hooks(), Traced.ActiveSpan.empty))
  }

  final implicit class TracedResourceOps[F[_]: Monad: Defer, A](resource: Resource[F, A])
                                                               (implicit t: Traced[F]) {
    def tracedLifetime(operation: String, tags: Traced.Tag*): Resource[F, A] =
      t.spanResource(operation, tags: _*).flatMap(_ => resource)

    def tracedUsage(operation: String, tags: Traced.Tag*): Resource[F, A] =
      resource.flatTap(_ => t.spanResource(operation, tags: _*))
  }

}
