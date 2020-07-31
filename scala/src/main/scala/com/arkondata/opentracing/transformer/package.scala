package com.arkondata.opentracing

import cats.data.StateT
import cats.effect.IO

import com.arkondata.opentracing.internal.TracedTTracedInstances

package object transformer extends TracedTTracedInstances {
  import com.arkondata.opentracing.internal.State

  type TracedT[F[_], A] = StateT[F, State, A]

  type TracedTIO[A] = TracedT[IO, A]
}
