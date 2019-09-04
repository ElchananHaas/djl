/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.mxnet.engine;

import com.sun.jna.Pointer;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.mxnet.jna.JnaUtils;
import software.amazon.ai.Context;
import software.amazon.ai.ndarray.NDArray;
import software.amazon.ai.ndarray.NDList;
import software.amazon.ai.ndarray.NDManager;
import software.amazon.ai.ndarray.types.DataType;
import software.amazon.ai.ndarray.types.Shape;
import software.amazon.ai.ndarray.types.SparseFormat;
import software.amazon.ai.util.PairList;

public class MxNDManager implements NDManager {

    /**
     * A global {@link NDManager} singleton instance.
     *
     * <p>This NDManager is the root of all the other {@code NDManager}s. NDArrays created by this
     * manager are un-managed, user has to close them manually. Those NDArrays will be released on
     * GC, and might be run into out of native memory issue.
     */
    private static final MxNDManager SYSTEM_MANAGER = new SystemManager();

    private static final NDList EMPTY = new NDList(0);

    private NDManager parent;
    private Context context;
    private Map<AutoCloseable, AutoCloseable> resources;
    private AtomicBoolean closed = new AtomicBoolean(false);

    private MxNDManager(NDManager parent, Context context) {
        this.parent = parent;
        this.context = Context.defaultIfNull(context);
        resources = new ConcurrentHashMap<>();
    }

    static MxNDManager getSystemManager() {
        return SYSTEM_MANAGER;
    }

    @Override
    public ByteBuffer allocateDirect(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    public MxNDArray create(Pointer handle) {
        MxNDArray array = new MxNDArray(this, handle);
        attach(array);
        return array;
    }

    public MxSparseNDArray create(Pointer handle, SparseFormat fmt) {
        MxSparseNDArray array = new MxSparseNDArray(this, handle, fmt);
        attach(array);
        return array;
    }

    /** {@inheritDoc} */
    @Override
    public MxNDArray create(Shape shape, DataType dataType, Context ctx) {
        ctx = Context.defaultIfNull(ctx, context);
        Pointer handle = JnaUtils.createNdArray(ctx, shape, dataType, shape.dimension(), false);
        MxNDArray array = new MxNDArray(this, handle, ctx, shape, dataType);
        attach(array);
        return array;
    }

    /** {@inheritDoc} */
    @Override
    public MxSparseNDArray createCSR(
            Buffer data, long[] indptr, long[] indices, Shape shape, Context ctx) {
        ctx = Context.defaultIfNull(ctx, context);

        SparseFormat fmt = SparseFormat.CSR;
        DataType dataType = DataType.fromBuffer(data);
        MxNDArray indptrNd = create(new Shape(indptr.length), DataType.INT64, ctx);
        indptrNd.set(indptr);
        MxNDArray indicesNd = create(new Shape(indices.length), DataType.INT64, ctx);
        indicesNd.set(indices);
        Pointer handle =
                JnaUtils.createSparseNdArray(
                        fmt,
                        ctx,
                        shape,
                        dataType,
                        new DataType[] {indptrNd.getDataType(), indicesNd.getDataType()},
                        new Shape[] {indptrNd.getShape(), indicesNd.getShape()},
                        false);
        MxSparseNDArray sparse = create(handle, fmt);
        MxNDArray dataNd = create(new Shape(data.remaining()), dataType, ctx);
        dataNd.set(data);
        JnaUtils.ndArraySyncCopyFromNdArray(sparse, dataNd, -1);
        JnaUtils.ndArraySyncCopyFromNdArray(sparse, indptrNd, 0);
        JnaUtils.ndArraySyncCopyFromNdArray(sparse, indicesNd, 1);
        return sparse;
    }

    /** {@inheritDoc} */
    @Override
    public MxSparseNDArray createRowSparse(
            Buffer data, Shape dataShape, long[] indices, Shape shape, Context ctx) {
        ctx = Context.defaultIfNull(ctx, context);

        SparseFormat fmt = SparseFormat.ROW_SPARSE;
        DataType dataType = DataType.fromBuffer(data);
        MxNDArray indicesNd = create(new Shape(indices.length), DataType.INT64, ctx);
        indicesNd.set(indices);
        Pointer handle =
                JnaUtils.createSparseNdArray(
                        fmt,
                        ctx,
                        shape,
                        dataType,
                        new DataType[] {indicesNd.getDataType()},
                        new Shape[] {indicesNd.getShape()},
                        false);
        MxSparseNDArray sparse = create(handle, fmt);
        MxNDArray dataNd = create(dataShape, dataType, ctx);
        dataNd.set(data);
        JnaUtils.ndArraySyncCopyFromNdArray(sparse, dataNd, -1);
        JnaUtils.ndArraySyncCopyFromNdArray(sparse, indicesNd, 0);
        return sparse;
    }

    /** {@inheritDoc} */
    @Override
    public NDList load(Path path) {
        return JnaUtils.loadNdArray(this, path);
    }

    /** {@inheritDoc} */
    @Override
    public void save(Path path, NDList ndList) {
        JnaUtils.saveNdArray(path, ndList);
    }

    /** {@inheritDoc} */
    @Override
    public NDArray zeros(Shape shape, DataType dataType, Context ctx) {
        return fill("_zeros", ctx, shape, dataType);
    }

    /** {@inheritDoc} */
    @Override
    public NDArray ones(Shape shape, DataType dataType, Context ctx) {
        return fill("_ones", ctx, shape, dataType);
    }

    /** {@inheritDoc} */
    @Override
    public NDArray arange(int start, int stop, int step, DataType dataType, Context ctx) {
        MxOpParams params = new MxOpParams();
        params.addParam("start", start);
        params.addParam("stop", stop);
        params.addParam("step", step);
        params.setDataType(dataType);
        params.setContext(Context.defaultIfNull(ctx, context));
        return invoke("_npi_arange", EMPTY, params).head();
    }

    /** {@inheritDoc} */
    @Override
    public NDArray eye(int rows, int cols, int k, DataType dataType, Context ctx) {
        MxOpParams params = new MxOpParams();
        params.addParam("N", rows);
        params.addParam("M", cols);
        params.addParam("k", k);
        params.setDataType(dataType);
        params.setContext(Context.defaultIfNull(ctx, context));
        return invoke("_npi_eye", EMPTY, params).head();
    }

    /** {@inheritDoc} */
    @Override
    public NDArray linspace(double start, double stop, int num, boolean endpoint, Context ctx) {
        if (num < 0) {
            throw new IllegalArgumentException("Num argument must be non-negative");
        }
        MxOpParams params = new MxOpParams();
        params.addParam("start", start);
        params.addParam("stop", stop);
        params.addParam("num", num);
        params.addParam("endpoint", endpoint);
        params.setDataType(DataType.FLOAT32);
        params.setContext(Context.defaultIfNull(ctx, context));
        return invoke("_npi_linspace", EMPTY, params).head();
    }

    /** {@inheritDoc} */
    @Override
    public NDArray randomUniform(
            double low, double high, Shape shape, DataType dataType, Context ctx) {
        MxOpParams params = new MxOpParams();
        params.addParam("low", low);
        params.addParam("high", high);
        params.addParam("shape", shape);
        params.setContext(Context.defaultIfNull(ctx, context));
        params.setDataType(dataType);
        return invoke("_npi_random_uniform", EMPTY, params).head();
    }

    /** {@inheritDoc} */
    @Override
    public NDArray randomNormal(
            double loc, double scale, Shape shape, DataType dataType, Context ctx) {
        MxOpParams params = new MxOpParams();
        params.addParam("loc", loc);
        params.addParam("scale", scale);
        params.addParam("shape", shape);
        params.setContext(Context.defaultIfNull(ctx, context));
        params.setDataType(dataType);
        return invoke("_npi_random_normal", EMPTY, params).head();
    }

    /** {@inheritDoc} */
    @Override
    public NDArray randomMultinomial(int n, NDArray pValues, Shape shape) {
        MxOpParams params = new MxOpParams();
        params.addParam("n", n);
        params.addParam("size", shape);
        return invoke("_npi_multinomial", pValues, params);
    }

    /** {@inheritDoc} */
    @Override
    public NDArray randomMultinomial(int n, NDArray pValues) {
        MxOpParams params = new MxOpParams();
        params.addParam("n", n);
        return invoke("_npi_multinomial", pValues, params);
    }

    public NDArray imread(String path, int flag) {
        MxOpParams params = new MxOpParams();
        params.addParam("filename", path);
        params.addParam("flag", flag);
        return invoke("_npi_cvimread", params);
    }

    /** {@inheritDoc} */
    @Override
    public NDManager getParentManager() {
        return parent;
    }

    /** {@inheritDoc} */
    @Override
    public MxNDManager newSubManager() {
        return newSubManager(context);
    }

    /** {@inheritDoc} */
    @Override
    public MxNDManager newSubManager(Context ctx) {
        MxNDManager manager = new MxNDManager(this, ctx);
        attach(manager);
        return manager;
    }

    /** {@inheritDoc} */
    @Override
    public Context getContext() {
        return context;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void attach(AutoCloseable resource) {
        if (closed.get()) {
            throw new IllegalStateException("NDManager has been closed already.");
        }
        resources.put(resource, resource);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void detach(AutoCloseable resource) {
        if (closed.get()) {
            throw new IllegalStateException("NDManager has been closed already.");
        }
        resources.remove(resource);
    }

    /** {@inheritDoc} */
    @Override
    public void invoke(String operation, NDList src, NDList dest, PairList<String, ?> params) {
        JnaUtils.op(operation).invoke(this, src.toArray(), dest.toArray(), params);
    }

    /** {@inheritDoc} */
    @Override
    public NDList invoke(String operation, NDList src, PairList<String, ?> params) {
        return new NDList(JnaUtils.op(operation).invoke(this, src.toArray(), params));
    }

    public NDArray invoke(String operation, NDArray src, PairList<String, ?> params) {
        return JnaUtils.op(operation).invoke(this, src, params)[0];
    }

    public NDArray invoke(String operation, PairList<String, ?> params) {
        return JnaUtils.op(operation).invoke(this, EMPTY.toArray(), params)[0];
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void close() {
        if (!closed.getAndSet(true)) {
            for (AutoCloseable resource : resources.keySet()) {
                try {
                    resource.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }
            parent.detach(this);
            resources.clear();
        }
    }

    boolean isOpen() {
        return !closed.get();
    }

    private NDArray fill(String opName, Context ctx, Shape shape, DataType dataType) {
        MxOpParams params = new MxOpParams();
        if (shape == null) {
            throw new IllegalArgumentException("Shape is required for " + opName.substring(1));
        }
        params.addParam("shape", shape);
        params.setContext(Context.defaultIfNull(ctx, context));
        params.setDataType(dataType);
        return invoke(opName, params);
    }

    private static final class SystemManager extends MxNDManager {

        SystemManager() {
            super(null, Context.defaultContext());
        }

        /** {@inheritDoc} */
        @Override
        public void attach(AutoCloseable resource) {}

        /** {@inheritDoc} */
        @Override
        public void detach(AutoCloseable resource) {}

        /** {@inheritDoc} */
        @Override
        public void close() {}
    }
}
