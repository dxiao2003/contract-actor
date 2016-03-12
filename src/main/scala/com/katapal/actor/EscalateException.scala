/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor

/** Only [[EscalateException]]'s thrown by a call handler are sent to the [[ContractActor]]'s supervisor,
  * other exceptions are sent back to the caller
  *
  * @param original The exception that generated is escalation.
  */
case class EscalateException(original: Throwable)
    extends Exception(s"Escalate exception: ${original}")
