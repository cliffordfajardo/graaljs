/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.test.builtins;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.runtime.JSContextOptions;

public class AtomicsBuiltinsTest {

    @Test
    public void testNotify() {
        int agentCount = 10;
        try (Context context = Context.newBuilder(JavaScriptLanguage.ID).allowExperimentalOptions(true).option(JSContextOptions.TEST262_MODE_NAME, "true").build()) {
            String code = "let agentCount = " + agentCount + ";\n" //
                            + "for (let i = 0; i < agentCount; i++) {\n" //
                            + "  $262.agent.start(`\n" //
                            + "    $262.agent.receiveBroadcast(function(sab) {\n" //
                            + "      const i32a = new Int32Array(sab);\n" //
                            + "      Atomics.wait(i32a, 0, 0);\n" //
                            + "      $262.agent.leaving();\n" //
                            + "    });\n" //
                            + "  `);\n" //
                            + "}\n" //
                            + "const sab = new SharedArrayBuffer(Int32Array.BYTES_PER_ELEMENT * 4);\n" //
                            + "$262.agent.broadcast(sab);\n" //
                            + "const i32a = new Int32Array(sab);\n" //
                            + "let count = 0;\n" //
                            + "while (count < agentCount) {\n" //
                            + "  count += Atomics.notify(i32a, 0);\n" //
                            + "}\n" //
                            + "count + Atomics.notify(i32a, 0);"; //
            Value result = context.eval(JavaScriptLanguage.ID, code);
            Assert.assertTrue(result.isNumber());
            // Atomics.notify() should report all agents eventually (but not more)
            Assert.assertEquals(agentCount, result.asInt());
        }
    }

}
