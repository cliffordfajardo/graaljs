/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.test.instrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Stack;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Before;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BinaryExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BuiltinRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBlockStatementTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowConditionStatementTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowStatementRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadElementExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteElementExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WritePropertyExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.EvalCallTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.FunctionCallExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ObjectAllocationExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadPropertyExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.UnaryExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadVariableExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteVariableExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralExpressionTag.Type;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

public abstract class FineGrainedAccessTest {

    private static final boolean DEBUG = false;

    protected static final String KEY = "key";
    protected static final String NAME = "name";
    protected static final String TYPE = "type";
    protected static final String OPERATOR = "operator";

    public static final Class<?>[] allJSSpecificTags = new Class[]{
                    ObjectAllocationExpressionTag.class,
                    BinaryExpressionTag.class,
                    UnaryExpressionTag.class,
                    ControlFlowStatementRootTag.class,
                    WriteVariableExpressionTag.class,
                    ReadElementExpressionTag.class,
                    WriteElementExpressionTag.class,
                    ReadPropertyExpressionTag.class,
                    WritePropertyExpressionTag.class,
                    ReadVariableExpressionTag.class,
                    LiteralExpressionTag.class,
                    FunctionCallExpressionTag.class,
                    BuiltinRootTag.class,
                    EvalCallTag.class,
                    ControlFlowStatementRootTag.class,
                    ControlFlowConditionStatementTag.class,
                    ControlFlowBlockStatementTag.class
    };

    @SuppressWarnings("unchecked")
    public static final String getTagNames(JavaScriptNode node) {
        String tags = "";

        if (node.hasTag(StandardTags.StatementTag.class)) {
            tags += "STMT ";
        }
        if (node.hasTag(StandardTags.RootTag.class)) {
            tags += "ROOT ";
        }
        for (Class<?> c : allJSSpecificTags) {
            if (node.hasTag((Class<? extends Tag>) c)) {
                tags += c.getSimpleName() + " ";
            }
        }
        return tags;
    }

    private Context context;
    private ArrayList<Event> events;
    private Stack<JavaScriptNode> stack;
    private Instrumenter instrumenter;
    private TestingExecutionInstrument instrument;
    private ExecutionEventNodeFactory factory;
    private EventBinding<ExecutionEventNodeFactory> binding;

    protected static class Event {
        enum Kind {
            INPUT,
            RETURN,
            ENTER,
        }

        protected final Kind kind;
        protected final Object val;
        protected final JavaScriptNode instrumentedNode;
        protected final EventContext context;

        public Event(EventContext context, Kind kind, JavaScriptNode instrumentedNode, Object inputValue) {
            if (DEBUG) {
                System.out.println("New event: " + kind + " === " + inputValue + "  === " + instrumentedNode.getClass().getSimpleName());
            }
            this.context = context;
            this.kind = kind;
            this.val = inputValue;
            this.instrumentedNode = instrumentedNode;
        }

        @Override
        public String toString() {
            return kind.name() + " " + val;
        }
    }

    private Event getNextEvent() {
        assertFalse("empty queue!", events.isEmpty());
        Event event = events.remove(0);
        return event;
    }

    protected void assertEngineInit() {
        // By default, we perform some operations to load Promises and other builtins written in js.
        for (int i = 0; i < 4; i++) {
            enter(BuiltinRootTag.class, (b) -> {
                assertAttribute(b, NAME, "Object.create");
            }).exit();
        }
    }

    static class AssertedEvent {

        private final Class<? extends Tag> tag;
        private final FineGrainedAccessTest test;

        AssertedEvent(FineGrainedAccessTest test, Class<? extends Tag> tag) {
            this.tag = tag;
            this.test = test;
        }

        AssertedEvent input(Object value) {
            Event event = test.getNextEvent();
            assertTrue(event.instrumentedNode.hasTag(tag));
            assertEquals(Event.Kind.INPUT, event.kind);
            assertEquals(event.val, value);
            return this;
        }

        AssertedEvent input() {
            Event event = test.getNextEvent();
            assertTrue(event.instrumentedNode.hasTag(tag));
            assertEquals(Event.Kind.INPUT, event.kind);
            return this;
        }

        AssertedEvent input(Consumer<Event> verify) {
            Event event = test.getNextEvent();
            assertTrue(event.instrumentedNode.hasTag(tag));
            assertEquals(Event.Kind.INPUT, event.kind);
            verify.accept(event);
            return this;
        }

        void exit() {
            Event event = test.getNextEvent();
            assertTrue(event.instrumentedNode.hasTag(tag));
            assertEquals(event.kind, Event.Kind.RETURN);
        }

        void exit(Consumer<Event> verify) {
            Event event = test.getNextEvent();
            assertTrue(event.instrumentedNode.hasTag(tag));
            assertEquals(event.kind, Event.Kind.RETURN);
            verify.accept(event);
        }
    }

    protected AssertedEvent enter(Class<? extends Tag> tag) {
        Event event = getNextEvent();
        assertTrue(event.instrumentedNode.hasTag(tag));
        assertEquals(event.kind, Event.Kind.ENTER);
        return new AssertedEvent(this, tag);
    }

    protected AssertedEvent enter(Class<? extends Tag> tag, Consumer<Event> verify) {
        Event event = getNextEvent();
        assertTrue(event.instrumentedNode.hasTag(tag));
        assertEquals(event.kind, Event.Kind.ENTER);
        verify.accept(event);
        return new AssertedEvent(this, tag);
    }

    protected AssertedEvent enter(Class<? extends Tag> tag, BiConsumer<Event, AssertedEvent> verify) {
        Event event = getNextEvent();
        assertTrue(event.instrumentedNode.hasTag(tag));
        assertEquals(event.kind, Event.Kind.ENTER);
        AssertedEvent chain = new AssertedEvent(this, tag);
        verify.accept(event, chain);
        return chain;
    }

    protected ExecutionEventNodeFactory getTestFactory() {
        return new ExecutionEventNodeFactory() {

            @Override
            public ExecutionEventNode create(EventContext c) {
                return new ExecutionEventNode() {

                    @Override
                    public void onEnter(VirtualFrame frame) {
                        events.add(new Event(c, Event.Kind.ENTER, (JavaScriptNode) c.getInstrumentedNode(), null));
                        stack.push((JavaScriptNode) c.getInstrumentedNode());
                    }

                    @Override
                    protected void onInputValue(VirtualFrame frame, EventContext inputContext, int inputIndex, Object inputValue) {
                        events.add(new Event(c, Event.Kind.INPUT, (JavaScriptNode) c.getInstrumentedNode(), inputValue));
                        saveInputValue(frame, inputIndex, inputValue);
                    }

                    @Override
                    protected void onReturnValue(VirtualFrame frame, Object result) {
                        Object[] values = getSavedInputValues(frame);
                        assertTrue(values != null);
                        if (values.length > 0) {
                            Object[] newValues = new Object[values.length + 1];
                            System.arraycopy(values, 0, newValues, 1, values.length);
                            newValues[0] = result;
                            events.add(new Event(c, Event.Kind.RETURN, (JavaScriptNode) c.getInstrumentedNode(), newValues));
                        } else {
                            events.add(new Event(c, Event.Kind.RETURN, (JavaScriptNode) c.getInstrumentedNode(), new Object[]{result}));
                        }
                        assert stack.pop() == c.getInstrumentedNode();
                    }
                };
            }
        };
    }

    protected static void assertAttribute(Event e, String attribute, Object expected) {
        Object val = getAttributeFrom(e.context, attribute);
        assertEquals(expected, val);
    }

    public static Object getAttributeFrom(EventContext cx, String name) {
        try {
            return ForeignAccess.sendRead(Message.READ.createNode(), (TruffleObject) ((InstrumentableNode) cx.getInstrumentedNode()).getNodeObject(), name);
        } catch (UnknownIdentifierException | UnsupportedMessageException e) {
            throw new RuntimeException(e);
        }
    }

    protected void evalWithTag(String src, Class<? extends Tag> tag) {
        evalWithTags(src, new Class[]{tag});
    }

    protected void evalAllTags(String src) {
        evalWithTags(src, allJSSpecificTags);
    }

    protected void evalWithTags(String src, Class<?>[] filterTags) {
        evalWithTags(src, filterTags, new Class[]{StandardTags.ExpressionTag.class});
    }

    protected void evalWithTags(String src, Class<?>[] sourceSectionTags, Class<?>[] inputGeneratingTags) {
        binding = initAgent(sourceSectionTags, inputGeneratingTags);
        context.eval("js", src);
    }

    @After
    public void disposeAgent() {
        assertTrue(events.isEmpty());
        assertTrue(stack.isEmpty());
        events.clear();
        binding.dispose();
    }

    @Before
    public void initTest() {
        context = Context.create("js");
        instrument = context.getEngine().getInstruments().get(TestingExecutionInstrument.ID).lookup(TestingExecutionInstrument.class);
        instrumenter = instrument.environment.getInstrumenter();
        events = new ArrayList<>();
        stack = new Stack<>();
        factory = getTestFactory();
    }

    private EventBinding<ExecutionEventNodeFactory> initAgent(Class<?>[] sourceSectionTags, Class<?>[] inputGeneratingTags) {
        SourceSectionFilter sourceSectionFilter = SourceSectionFilter.newBuilder().tagIs(sourceSectionTags).build();
        SourceSectionFilter inputGeneratingFilter = SourceSectionFilter.newBuilder().tagIs(inputGeneratingTags).build();
        return instrumenter.attachExecutionEventFactory(sourceSectionFilter, inputGeneratingFilter, factory);
    }

    // === common asserts

    protected static final Consumer<Event> assertReturnValue(Object expected) {
        Consumer<Event> c = (e) -> {
            assertTrue(e.val instanceof Object[]);
            Object[] vals = (Object[]) e.val;
            assertEquals(vals[0], expected);
        };
        return c;
    }

    protected static final Consumer<Event> assertLiteralType(LiteralExpressionTag.Type type) {
        Consumer<Event> c = (e) -> {
            assertAttribute(e, TYPE, type.name());
        };
        return c;
    }

    protected static final Consumer<Event> assertPropertyReadName(String name) {
        Function<String, Consumer<Event>> fun = (e) -> {
            return (x) -> {
                assertAttribute(x, KEY, e);
            };
        };
        return fun.apply(name);
    }

    protected static final Consumer<Event> assertVarReadName(String name) {
        Function<String, Consumer<Event>> fun = (e) -> {
            return (x) -> {
                assertAttribute(x, NAME, e);
            };
        };
        return fun.apply(name);
    }

    protected static final Consumer<Event> assertJSObjectInput = (e) -> {
        assertTrue(!JSFunction.isJSFunction(e.val));
        assertTrue(!JSArray.isJSArray(e.val));
        assertTrue(JSObject.isJSObject(e.val));
    };

    protected static final Consumer<Event> assertJSArrayInput = (e) -> {
        assertTrue(JSObject.isJSObject(e.val));
        assertTrue(JSArray.isJSArray(e.val));
    };

    protected static final Consumer<Event> assertUndefinedInput = (e) -> {
        assertEquals(e.val, Undefined.instance);
    };

    protected static final Consumer<Event> assertGlobalObjectInput = (e) -> {
        assertTrue(JSObject.isJSObject(e.val));
        DynamicObject globalObject = JSObject.getJSContext((DynamicObject) e.val).getRealm().getGlobalObject();
        assertEquals(globalObject, e.val);
    };

    protected static final Consumer<Event> assertJSFunctionInput = (e) -> {
        assertTrue(JSFunction.isJSFunction(e.val));
    };

    protected static final Consumer<Event> assertJSFunctionReturn = (e) -> {
        assertTrue(e.val instanceof Object[]);
        Object[] vals = (Object[]) e.val;
        assertTrue(JSFunction.isJSFunction(vals[0]));
    };

    protected void assertGlobalVarDeclaration(String name, Object value) {
        enter(WritePropertyExpressionTag.class, (e, write) -> {
            assertAttribute(e, KEY, name);
            write.input(assertGlobalObjectInput);
            enter(LiteralExpressionTag.class, (e2) -> {
                if (value instanceof Integer) {
                    assertAttribute(e2, TYPE, Type.NumericLiteral.name());
                } else if (value instanceof Boolean) {
                    assertAttribute(e2, TYPE, Type.BooleanLiteral.name());
                } else if (value instanceof String) {
                    assertAttribute(e2, TYPE, Type.StringLiteral.name());
                }
            }).exit();
            write.input(value);
        }).exit();
    }

    protected void assertGlobalFunctionExpressionDeclaration(String name) {
        enter(WritePropertyExpressionTag.class, (e, write) -> {
            write.input(assertGlobalObjectInput);
            enter(LiteralExpressionTag.class).exit((e1) -> {
                assertAttribute(e1, TYPE, LiteralExpressionTag.Type.FunctionLiteral.name());
                Object[] results = (Object[]) e1.val;
                assertTrue(results.length == 1);
                assertTrue(JSFunction.isJSFunction(results[0]));
            });
            assertAttribute(e, KEY, name);
            write.input(assertJSFunctionInput);
        }).exit();
    }

    protected void assertGlobalArrayLiteralDeclaration(String name) {
        enter(WritePropertyExpressionTag.class, (e, write) -> {
            assertAttribute(e, KEY, name);
            write.input(assertGlobalObjectInput);
            enter(LiteralExpressionTag.class).exit();
            write.input(assertJSArrayInput);
        }).exit();
    }

}
