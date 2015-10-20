/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.scalar.cast;

import io.crate.exceptions.ConversionException;
import io.crate.metadata.*;
import io.crate.operation.Input;
import io.crate.operation.scalar.ScalarFunctionModule;
import io.crate.planner.symbol.Function;
import io.crate.planner.symbol.Literal;
import io.crate.planner.symbol.Symbol;
import io.crate.types.DataType;

import java.util.List;
import java.util.Map;

public class TryCastScalarFunction extends Scalar<Object, Object> {

    private final DataType returnType;
    private final FunctionInfo info;

    private TryCastScalarFunction(FunctionInfo functionInfo) {
        this.returnType = functionInfo.returnType();
        this.info = functionInfo;
    }

    public static void register(ScalarFunctionModule module) {
        for (Map.Entry<DataType, String> function : CastFunctionResolver.tryFunctionsMap().entrySet()) {
            module.register(function.getValue(), new Resolver(function.getKey(), function.getValue()));
        }
    }

    private static class Resolver implements DynamicFunctionResolver {

        private final String name;
        private final DataType dataType;

        protected Resolver(DataType dataType, String name) {
            this.name = name;
            this.dataType = dataType;
        }

        @Override
        public FunctionImplementation<Function> getForTypes(List<DataType> dataTypes) throws IllegalArgumentException {
            return new TryCastScalarFunction(new FunctionInfo(new FunctionIdent(name, dataTypes), dataType));
        }
    }

    @Override
    public FunctionInfo info() {
        return info;
    }

    @Override
    public Symbol normalizeSymbol(Function symbol) {
        assert symbol.arguments().size() == 1;
        Symbol argument = symbol.arguments().get(0);
        if (argument.symbolType().isValueSymbol()) {
            Object value = ((Input) argument).value();
            try {
                return Literal.newLiteral(returnType, returnType.value(value));
            } catch (ClassCastException | IllegalArgumentException | ConversionException e) {
                return Literal.NULL;
            }
        }
        return symbol;
    }

    @Override
    public Object evaluate(Input[] args) {
        assert args.length == 1;
        try {
            return returnType.value(args[0].value());
        } catch (ClassCastException | IllegalArgumentException | ConversionException e) {
            return null;
        }
    }
}
