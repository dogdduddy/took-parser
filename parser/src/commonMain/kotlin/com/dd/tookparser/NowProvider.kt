package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

typealias NowProvider = () -> Instant

val defaultNowProvider: NowProvider = { Clock.System.now() }
