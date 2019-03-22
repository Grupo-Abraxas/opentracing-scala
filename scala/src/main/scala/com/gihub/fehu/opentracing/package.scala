package com.gihub.fehu

import scala.language.higherKinds

import cats.{ Eval, Later }
import io.opentracing.Tracer

package object opentracing {

  def trace(implicit tracing: Tracing[Later, Eval], tracer: Tracer): TraceLaterEval.Ops = new TraceLaterEval.Ops

  implicit class TraceOps[F0[_], A, F1[_]](fa: F0[A])(implicit val trace: Tracing[F0, F1], tracer: Tracer) {
    def tracing: trace.PartiallyApplied[A] = trace(fa)
  }

}