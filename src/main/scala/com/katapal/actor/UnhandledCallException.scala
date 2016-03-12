/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor

import com.katapal.actor.ContractActor.Returning

/** Sent to the caller when callback (a [[PartialFunction]]) does not accept the received response as input. */
case class UnhandledCallException(c: Returning[_]) extends Throwable
