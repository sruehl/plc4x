/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package org.apache.plc4x.java.modbus.netty;

import com.digitalpetri.modbus.ModbusPdu;
import com.digitalpetri.modbus.codec.ModbusTcpPayload;
import com.digitalpetri.modbus.requests.*;
import com.digitalpetri.modbus.responses.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.messages.items.ReadResponseItem;
import org.apache.plc4x.java.api.messages.items.ResponseItem;
import org.apache.plc4x.java.api.messages.items.WriteResponseItem;
import org.apache.plc4x.java.api.types.ResponseCode;
import org.apache.plc4x.java.modbus.model.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.plc4x.java.base.util.Assert.assertByteEquals;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class Plc4XModbusProtocolTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(Plc4XModbusProtocolTest.class);

    public static final Calendar calenderInstance = Calendar.getInstance();

    private Plc4XModbusProtocol SUT;

    @Parameterized.Parameter
    public String payloadClazzName;

    @Parameterized.Parameter(1)
    public PlcRequestContainer<PlcRequest, PlcResponse> plcRequestContainer;

    @Parameterized.Parameter(2)
    public CompletableFuture completableFuture;

    @Parameterized.Parameter(3)
    public String plcRequestContainerClassName;

    @Parameterized.Parameter(4)
    public ModbusTcpPayload modbusTcpPayload;

    @Parameterized.Parameter(5)
    public String modbusPduName;

    @Parameterized.Parameters(name = "{index} Type:{0} {3} {5}")
    public static Collection<Object[]> data() {
        return Stream.of(
            Boolean.class,
            Byte.class,
            Short.class,
            //Calendar.class,
            //Float.class,
            Integer.class //,
            //String.class
        )
            .map(clazz -> {
                if (clazz == Boolean.class) {
                    return ImmutablePair.of(Boolean.TRUE, new byte[]{0x01});
                } else if (clazz == Byte.class) {
                    return ImmutablePair.of(Byte.valueOf("1"), new byte[]{0x1});
                } else if (clazz == Short.class) {
                    return ImmutablePair.of(Short.valueOf("1"), new byte[]{0x1, 0x0});
                } else if (clazz == Calendar.class) {
                    return ImmutablePair.of(calenderInstance, new byte[]{0x0, 0x0, 0x0, 0x0, 0x4, 0x3, 0x2, 0x1});
                } else if (clazz == Float.class) {
                    return ImmutablePair.of(Float.valueOf("1"), new byte[]{0x0, 0x0, (byte) 0x80, 0x3F});
                } else if (clazz == Integer.class) {
                    return ImmutablePair.of(Integer.valueOf("1"), new byte[]{0x1, 0x0, 0x0, 0x0});
                } else if (clazz == String.class) {
                    return ImmutablePair.of(String.valueOf("Hello World!"), new byte[]{0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x57, 0x6f, 0x72, 0x6c, 0x64, 0x21, 0x00});
                } else {
                    throw new IllegalArgumentException("Unmapped type " + clazz);
                }
            })
            .map(pair -> Stream.of(
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcReadRequest
                            .builder()
                            .addItem(pair.left.getClass(), CoilModbusAddress.of("coil:1"))
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new ReadCoilsResponse(Unpooled.wrappedBuffer(pair.right)))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcWriteRequest
                            .builder()
                            .addItem(CoilModbusAddress.of("coil:1"), pair.left)
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new WriteSingleCoilResponse(1, pair.right[0]))
                ),
                /* Read request no supported on maskwrite so how to handle?
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcReadRequest
                            .builder()
                            .addItem(pair.left.getClass(), MaskWriteRegisterModbusAddress.of("maskwrite:1/1/2"))
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new MaskWriteRegisterResponse(1, 1, 2))
                ), */
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcWriteRequest
                            .builder()
                            .addItem(MaskWriteRegisterModbusAddress.of("maskwrite:1/1/2"), pair.left)
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new MaskWriteRegisterResponse(1, 1, 2))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcReadRequest
                            .builder()
                            .addItem(pair.left.getClass(), ReadDiscreteInputsModbusAddress.of("readdiscreteinputs:1"))
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new ReadDiscreteInputsResponse(Unpooled.wrappedBuffer(pair.right)))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcReadRequest
                            .builder()
                            .addItem(pair.left.getClass(), ReadHoldingRegistersModbusAddress.of("readholdingregisters:1"))
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new ReadHoldingRegistersResponse(Unpooled.wrappedBuffer(evenUp(pair.right))))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcReadRequest
                            .builder()
                            .addItem(pair.left.getClass(), ReadInputRegistersModbusAddress.of("readinputregisters:1"))
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new ReadInputRegistersResponse(Unpooled.wrappedBuffer(evenUp(pair.right))))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcWriteRequest
                            .builder()
                            .addItem((Class) pair.left.getClass(), CoilModbusAddress.of("coil:1"), pair.left, pair.left, pair.left)
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new WriteMultipleCoilsResponse(1, 3))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcWriteRequest
                            .builder()
                            .addItem((Class) pair.left.getClass(), RegisterModbusAddress.of("register:1"), pair.left, pair.left, pair.left)
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new WriteMultipleCoilsResponse(1, 3))
                ),
                ImmutablePair.of(
                    new PlcRequestContainer<>(
                        PlcWriteRequest
                            .builder()
                            .addItem(RegisterModbusAddress.of("register:1"), pair.left)
                            .build(), new CompletableFuture<>()),
                    new ModbusTcpPayload((short) 0, (short) 0, new WriteSingleCoilResponse(1, pair.right[0]))
                )
            ))
            .flatMap(stream -> stream)
            .map(pair -> new Object[]{pair.left.getRequest().getRequestItem().orElseThrow(IllegalStateException::new).getDatatype().getSimpleName(), pair.left, pair.left.getResponseFuture(), pair.left.getRequest().getClass().getSimpleName(), pair.right, pair.right.getModbusPdu().getClass().getSimpleName()}).collect(Collectors.toList());
    }

    private static byte[] evenUp(byte[] bytes) {
        if (bytes.length % 2 == 0) {
            return bytes;
        } else {
            return ArrayUtils.insert(0, bytes, (byte) 0x0);
        }
    }

    @Before
    public void setUp() {
        SUT = new Plc4XModbusProtocol();
    }

    @Test
    public void encode() throws Exception {
        ArrayList<Object> out = new ArrayList<>();
        SUT.encode(null, plcRequestContainer, out);
        assertThat(out, hasSize(1));
        assertThat(out.get(0), instanceOf(ModbusTcpPayload.class));
        ModbusTcpPayload modbusTcpPayload = (ModbusTcpPayload) out.get(0);
        ModbusPdu modbusPdu = modbusTcpPayload.getModbusPdu();
        if (modbusPdu instanceof MaskWriteRegisterRequest) {
            MaskWriteRegisterRequest maskWriteRegisterRequest = (MaskWriteRegisterRequest) modbusPdu;
            int address = maskWriteRegisterRequest.getAddress();
            assertThat(address, equalTo(1));
            int andMask = maskWriteRegisterRequest.getAndMask();
            int orMask = maskWriteRegisterRequest.getOrMask();
            if (payloadClazzName.equals(Boolean.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(Byte.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(Short.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(Calendar.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(Float.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(Integer.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            } else if (payloadClazzName.equals(String.class.getSimpleName())) {
                assertThat(andMask, equalTo(1));
                assertThat(orMask, equalTo(2));
            }
        } else if (modbusPdu instanceof ReadCoilsRequest) {
            ReadCoilsRequest readCoilsRequest = (ReadCoilsRequest) modbusPdu;
            int address = readCoilsRequest.getAddress();
            int quantity = readCoilsRequest.getQuantity();
            assertThat(address, equalTo(1));
            assertThat(quantity, equalTo(1));
        } else if (modbusPdu instanceof ReadDiscreteInputsRequest) {
            ReadDiscreteInputsRequest readCoilsRequest = (ReadDiscreteInputsRequest) modbusPdu;
            int address = readCoilsRequest.getAddress();
            int quantity = readCoilsRequest.getQuantity();
            assertThat(address, equalTo(1));
            assertThat(quantity, equalTo(1));
        } else if (modbusPdu instanceof ReadHoldingRegistersRequest) {
            ReadHoldingRegistersRequest readCoilsRequest = (ReadHoldingRegistersRequest) modbusPdu;
            int address = readCoilsRequest.getAddress();
            int quantity = readCoilsRequest.getQuantity();
            assertThat(address, equalTo(1));
            assertThat(quantity, equalTo(1));
        } else if (modbusPdu instanceof ReadInputRegistersRequest) {
            ReadInputRegistersRequest readCoilsRequest = (ReadInputRegistersRequest) modbusPdu;
            int address = readCoilsRequest.getAddress();
            int quantity = readCoilsRequest.getQuantity();
            assertThat(address, equalTo(1));
            assertThat(quantity, equalTo(1));
        } else if (modbusPdu instanceof WriteMultipleCoilsRequest) {
            WriteMultipleCoilsRequest writeMultipleCoilsRequest = (WriteMultipleCoilsRequest) modbusPdu;
            int address = writeMultipleCoilsRequest.getAddress();
            assertThat(address, equalTo(1));
            ByteBuf value = writeMultipleCoilsRequest.getValues();
            byte[] bytes = new byte[value.readableBytes()];
            value.readBytes(value);
            if (payloadClazzName.equals(Boolean.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(Byte.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(Short.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(Calendar.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(Float.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(Integer.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            } else if (payloadClazzName.equals(String.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0}, bytes);
            }
        } else if (modbusPdu instanceof WriteMultipleRegistersRequest) {
            WriteMultipleRegistersRequest writeMultipleRegistersRequest = (WriteMultipleRegistersRequest) modbusPdu;
            int address = writeMultipleRegistersRequest.getAddress();
            assertThat(address, equalTo(1));
            ByteBuf value = writeMultipleRegistersRequest.getValues();
            byte[] bytes = new byte[value.readableBytes()];
            value.readBytes(value);
            if (payloadClazzName.equals(Boolean.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(Byte.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(Short.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(Calendar.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(Float.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(Integer.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            } else if (payloadClazzName.equals(String.class.getSimpleName())) {
                assertByteEquals(new byte[]{0x0, 0x0, 0x0, 0x0, 0x0, 0x0}, bytes);
            }
        } else if (modbusPdu instanceof WriteSingleCoilRequest) {
            WriteSingleCoilRequest writeSingleCoilRequest = (WriteSingleCoilRequest) modbusPdu;
            int address = writeSingleCoilRequest.getAddress();
            assertThat(address, equalTo(1));
            int value = writeSingleCoilRequest.getValue();
            boolean coilValue = value == 0xFF00;
            if (payloadClazzName.equals(Boolean.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(Byte.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(Short.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(Calendar.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(Float.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(Integer.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            } else if (payloadClazzName.equals(String.class.getSimpleName())) {
                assertThat(coilValue, equalTo(true));
            }
        } else if (modbusPdu instanceof WriteSingleRegisterRequest) {
            WriteSingleRegisterRequest writeSingleRegisterRequest = (WriteSingleRegisterRequest) modbusPdu;
            int address = writeSingleRegisterRequest.getAddress();
            assertThat(address, equalTo(1));
            int value = writeSingleRegisterRequest.getValue();
            if (payloadClazzName.equals(Boolean.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(Byte.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(Short.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(Calendar.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(Float.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(Integer.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            } else if (payloadClazzName.equals(String.class.getSimpleName())) {
                assertThat(value, equalTo(1));
            }
        }
    }

    @Test
    public void decode() throws Exception {
        ArrayList<Object> in = new ArrayList<>();
        SUT.encode(null, plcRequestContainer, in);
        assertThat(in, hasSize(1));
        syncInvoiceId();
        ArrayList<Object> out = new ArrayList<>();
        SUT.decode(null, modbusTcpPayload, out);
        assertThat(out, hasSize(0));
        LOGGER.info("PlcRequestContainer {}", plcRequestContainer);
        PlcResponse plcResponse = plcRequestContainer.getResponseFuture().get();
        ResponseItem responseItem = (ResponseItem) plcResponse.getResponseItem().get();
        LOGGER.info("ResponseItem {}", responseItem);
        ModbusPdu modbusPdu = modbusTcpPayload.getModbusPdu();
        if (modbusPdu instanceof MaskWriteRegisterRequest) {
            WriteResponseItem writeResponseItem = (WriteResponseItem) responseItem;
            assertEquals(ResponseCode.OK, writeResponseItem.getResponseCode());
        } else if (modbusPdu instanceof ReadCoilsRequest) {
            ReadResponseItem readResponseItem = (ReadResponseItem) responseItem;
            Object value = readResponseItem.getValues().get(0);
            defaultAssert(value);
        } else if (modbusPdu instanceof ReadDiscreteInputsRequest) {
            ReadResponseItem readResponseItem = (ReadResponseItem) responseItem;
            Object value = readResponseItem.getValues().get(0);
            defaultAssert(value);
        } else if (modbusPdu instanceof ReadHoldingRegistersRequest) {
            ReadResponseItem readResponseItem = (ReadResponseItem) responseItem;
            Object value = readResponseItem.getValues().get(0);
            defaultAssert(value);
        } else if (modbusPdu instanceof ReadInputRegistersRequest) {
            ReadResponseItem readResponseItem = (ReadResponseItem) responseItem;
            Object value = readResponseItem.getValues().get(0);
            defaultAssert(value);
        } else if (modbusPdu instanceof WriteMultipleCoilsRequest) {
            WriteResponseItem writeResponseItem = (WriteResponseItem) responseItem;
            assertEquals(ResponseCode.OK, writeResponseItem.getResponseCode());
        } else if (modbusPdu instanceof WriteMultipleRegistersRequest) {
            WriteResponseItem writeResponseItem = (WriteResponseItem) responseItem;
            assertEquals(ResponseCode.OK, writeResponseItem.getResponseCode());
        } else if (modbusPdu instanceof WriteSingleCoilResponse) {
            WriteResponseItem writeResponseItem = (WriteResponseItem) responseItem;
            assertEquals(ResponseCode.OK, writeResponseItem.getResponseCode());
        } else if (modbusPdu instanceof WriteSingleRegisterRequest) {
            WriteResponseItem writeResponseItem = (WriteResponseItem) responseItem;
            assertEquals(ResponseCode.OK, writeResponseItem.getResponseCode());
        }
    }

    private void defaultAssert(Object value) {
        assertPayloadDependentEquals(Boolean.class, value, true);
        assertPayloadDependentEquals(Byte.class, value, Byte.valueOf("1"));
        assertPayloadDependentEquals(Short.class, value, Short.valueOf("1"));
        assertPayloadDependentEquals(Calendar.class, value, calenderInstance);
        assertPayloadDependentEquals(Float.class, value, Float.valueOf("1"));
        assertPayloadDependentEquals(Integer.class, value, Integer.valueOf("1"));
        assertPayloadDependentEquals(String.class, value, String.valueOf("Hello World!"));
    }

    private <T> void assertPayloadDependentEquals(Class<T> expectedType, Object actual, T expected) {
        if (!payloadClazzName.equalsIgnoreCase(expectedType.getSimpleName())) {
            return;
        }
        assertThat(actual, equalTo(expected));
    }

    private void syncInvoiceId() throws Exception {
        Field transactionId = SUT.getClass().getDeclaredField("transactionId");
        transactionId.setAccessible(true);
        AtomicInteger correlationBuilder = (AtomicInteger) transactionId.get(SUT);

        Field invokeIdField = ModbusTcpPayload.class.getDeclaredField("transactionId");
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(invokeIdField, invokeIdField.getModifiers() & ~Modifier.FINAL);
        invokeIdField.setAccessible(true);
        invokeIdField.set(modbusTcpPayload, (short) (correlationBuilder.get() - 1));
    }
}