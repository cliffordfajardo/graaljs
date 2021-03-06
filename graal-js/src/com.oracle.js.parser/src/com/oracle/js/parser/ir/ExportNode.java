/*
 * Copyright (c) 2010, 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.js.parser.ir;

import com.oracle.js.parser.ir.visitor.NodeVisitor;
import com.oracle.js.parser.ir.visitor.TranslatorNodeVisitor;

public class ExportNode extends Node {

    private final ExportClauseNode exportClause;

    private final FromNode from;

    private final IdentNode exportIdent;

    private final VarNode var;

    private final Expression expression;

    private final boolean isDefault;

    public ExportNode(final long token, final int start, final int finish, final FromNode from) {
        this(token, start, finish, null, from, null, null, null, false);
    }

    public ExportNode(final long token, final int start, final int finish, final ExportClauseNode exportClause, final FromNode from) {
        this(token, start, finish, exportClause, from, null, null, null, false);
    }

    public ExportNode(final long token, final int start, final int finish, final IdentNode ident, final Expression expression, final boolean isDefault) {
        this(token, start, finish, null, null, ident, null, expression, isDefault);
    }

    public ExportNode(final long token, final int start, final int finish, final IdentNode ident, final VarNode var) {
        this(token, start, finish, null, null, ident, var, null, false);
    }

    private ExportNode(final long token, final int start, final int finish, final ExportClauseNode exportClause,
                    final FromNode from, final IdentNode exportIdent, final VarNode var, final Expression expression, final boolean isDefault) {
        super(token, start, finish);
        this.exportClause = exportClause;
        this.from = from;
        this.exportIdent = exportIdent;
        this.var = var;
        this.expression = expression;
        this.isDefault = isDefault;
        assert (exportClause != null || from != null) != (exportIdent != null);
        assert !isDefault || (exportClause == null && from == null);
        assert (exportIdent == null && var == null && expression == null) || isDefault || (exportIdent != null && exportIdent == getIdent(var, expression));
    }

    private ExportNode(final ExportNode node, final ExportClauseNode exportClause,
                    final FromNode from, final IdentNode exportIdent, final VarNode var, final Expression expression) {
        super(node);
        this.isDefault = node.isDefault;

        this.exportClause = exportClause;
        this.from = from;
        this.exportIdent = exportIdent;
        this.var = var;
        this.expression = expression;
    }

    public ExportClauseNode getExportClause() {
        return exportClause;
    }

    public FromNode getFrom() {
        return from;
    }

    public IdentNode getExportIdentifier() {
        return exportIdent;
    }

    public VarNode getVar() {
        return var;
    }

    public Expression getExpression() {
        return expression;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public ExportNode setExportClause(ExportClauseNode exportClause) {
        assert exportIdent == null;
        if (this.exportClause == exportClause) {
            return this;
        }
        return new ExportNode(this, exportClause, from, exportIdent, var, expression);
    }

    public ExportNode setFrom(FromNode from) {
        assert exportIdent == null;
        if (this.from == from) {
            return this;
        }
        return new ExportNode(this, exportClause, from, exportIdent, var, expression);
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterExportNode(this)) {
            ExportClauseNode newExportClause = exportClause == null ? null : (ExportClauseNode) exportClause.accept(visitor);
            FromNode newFrom = from == null ? null : (FromNode) from.accept(visitor);
            VarNode newVar = var == null ? null : (VarNode) var.accept(visitor);
            Expression newExpression = expression == null ? null : (Expression) expression.accept(visitor);
            IdentNode newIdent = (exportIdent == null || isDefault()) ? exportIdent : getIdent(newVar, newExpression);
            ExportNode newNode = (this.exportClause == newExportClause && this.from == newFrom && this.exportIdent == newIdent && this.var == newVar && this.expression == newExpression)
                            ? this
                            : new ExportNode(this, exportClause, from, exportIdent, var, expression);
            return visitor.leaveExportNode(newNode);
        }

        return this;
    }

    private static IdentNode getIdent(VarNode newVar, Expression newExpression) {
        if (newVar != null) {
            return newVar.getName();
        } else if (newExpression instanceof FunctionNode) {
            return ((FunctionNode) newExpression).getIdent();
        } else if (newExpression instanceof ClassNode) {
            return ((ClassNode) newExpression).getIdent();
        }
        return null;
    }

    @Override
    public <R> R accept(TranslatorNodeVisitor<? extends LexicalContext, R> visitor) {
        return visitor.enterExportNode(this);
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        sb.append("export ");
        if (isDefault) {
            sb.append("default ");
        }
        if (expression != null) {
            expression.toString(sb, printType);
            if (expression.isAssignment()) {
                sb.append(';');
            }
        } else {
            if (exportClause == null) {
                sb.append("* ");
            } else {
                exportClause.toString(sb, printType);
            }
            if (from != null) {
                from.toString(sb, printType);
            }
            sb.append(';');
        }
    }

}
