package io.github.andrealtb.artwork.am;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/**
 * The two ways a picked video becomes a cover file: an exact copy of its video samples, or a decode,
 * crop and encode through one GLES pass. Both write video only, with time starting at zero, and run on
 * the caller's worker thread; the source extractor is owned by the caller.
 */
final class AmTranscoder {
    /** One encode: the codecs, the square side, the rate budget and how the source frame is stored. */
    record Plan(String decoder, String encoder, boolean highProfile, int side, int bitrate, int frameRate,
            int width, int height, int rotation, boolean hdr) {}

    private static final long TIMEOUT_US = 10_000;
    private static final long STALL_MS = 10_000;
    private static final long DEADLINE_MS = 6 * 60_000;
    /** Leaves room for the last frame's own length, so the track never reports more than the contract allows. */
    private static final long LAST_FRAME_US = AmLocalPolicy.MAX_DURATION_MS * 1000 - 100_000;

    private AmTranscoder() {}

    /** Copies the selected track sample for sample. Anything an exact copy cannot carry is refused. */
    static void remux(MediaExtractor extractor, MediaFormat format, File output, long maxBytes,
            BooleanSupplier cancelled) throws AmFailure {
        MediaMuxer muxer = null;
        boolean started = false;
        String stage = "remux_create";
        try {
            muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            stage = "remux_add_track";
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? Math.max(64 * 1024, Math.min(8 * 1024 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)))
                    : 4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            stage = "remux_first_sample";
            long origin = extractor.getSampleTime();
            if (origin < 0 || (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                throw new AmFailure(Status.UNSUPPORTED, "local_remux_unsupported", 0, "stage=remux_first_sample");
            }
            long written = 0;
            int samples = 0;
            stage = "remux_samples";
            while (extractor.getSampleTime() >= 0) {
                if (cancelled.getAsBoolean()) throw cancelled();
                long pts = extractor.getSampleTime() - origin;
                int flags = extractor.getSampleFlags();
                long size = extractor.getSampleSize();
                // A sample before the first key frame needs an edit list the muxer cannot write.
                if (pts < 0 || size <= 0 || size > buffer.capacity() || ++samples > 10_000
                        || (flags & (MediaExtractor.SAMPLE_FLAG_ENCRYPTED | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)) != 0) {
                    throw new AmFailure(Status.UNSUPPORTED, "local_remux_unsupported", 0, "stage=remux_sample");
                }
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read != size) throw new AmFailure(Status.UNSUPPORTED, "local_remux_unsupported", 0, "stage=remux_read");
                info.set(0, read, pts, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(track, buffer, info);
                if ((written += read) > maxBytes) throw new AmFailure(Status.UNSUPPORTED, "local_too_large");
                if (!extractor.advance()) break;
            }
            if (samples == 0) throw new AmFailure(Status.UNSUPPORTED, "local_remux_unsupported", 0, "stage=remux_empty");
            stage = "remux_stop";
            muxer.stop();
            started = false;
        } catch (AmFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AmFailure(Status.UNSUPPORTED, "local_remux_unsupported", 0, AmExceptionDiagnostic.describe(stage, error));
        } finally {
            if (muxer != null) {
                if (started) try { muxer.stop(); } catch (RuntimeException ignored) { /* the partial file is discarded */ }
                try { muxer.release(); } catch (RuntimeException ignored) { /* released either way */ }
            }
        }
    }

    /** Decodes the selected track, draws each kept frame cropped into the square and encodes it as H.264. */
    static void transcode(MediaExtractor extractor, MediaFormat input, Plan plan, File output, long maxBytes,
            LongConsumer progressUs, BooleanSupplier cancelled) throws AmFailure {
        new Job(plan, output, maxBytes, progressUs, cancelled).run(extractor, input);
    }

    private static AmFailure cancelled() { return new AmFailure(Status.ERROR, "cancelled"); }

    /** All codec, surface and GL state of one encode; released in reverse order of creation. */
    private static final class Job {
        final Plan plan;
        final File output;
        final long maxBytes;
        final LongConsumer progress;
        final BooleanSupplier cancelled;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec decoder, encoder;
        Surface encoderSurface, decoderSurface;
        SurfaceTexture texture;
        HandlerThread frames;
        Egl egl;
        MediaMuxer muxer;
        int track = -1;
        boolean decoderStarted, encoderStarted, muxing;
        long written;
        long lastOutputAt;
        String stage = "encoder_create";

        Job(Plan plan, File output, long maxBytes, LongConsumer progress, BooleanSupplier cancelled) {
            this.plan = plan; this.output = output; this.maxBytes = maxBytes; this.progress = progress; this.cancelled = cancelled;
        }

        void run(MediaExtractor extractor, MediaFormat input) throws AmFailure {
            try {
                startEncoder();
                stage = "egl";
                egl = new Egl(encoderSurface);
                Renderer renderer = new Renderer(plan.side());
                frames = new HandlerThread("artwork-local-frames");
                frames.start();
                FrameWaiter waiter = new FrameWaiter();
                texture = new SurfaceTexture(renderer.texture);
                texture.setOnFrameAvailableListener(waiter, new Handler(frames.getLooper()));
                decoderSurface = new Surface(texture);
                startDecoder(input);
                stage = "muxer_create";
                muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                loop(extractor, renderer, waiter);
                stage = "muxer_stop";
                if (!muxing) throw new AmFailure(Status.UNSUPPORTED, "local_transcode_failed", 0, "stage=no_output");
                muxer.stop();
                muxing = false;
            } catch (AmFailure failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled();
            } catch (Exception error) {
                throw new AmFailure(Status.UNSUPPORTED, "local_transcode_failed", 0, AmExceptionDiagnostic.describe(stage, error));
            } finally {
                release();
            }
        }

        private void startEncoder() throws Exception {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.side(), plan.side());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate());
            format.setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate());
            // One key frame a second: the lock screen restarts the loop by seeking to the start.
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            encoder = MediaCodec.createByCodecName(plan.encoder());
            stage = "encoder_configure";
            if (plan.highProfile()) {
                MediaFormat high = new MediaFormat(format);
                high.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                try { encoder.configure(high, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
                catch (RuntimeException rejected) {
                    // Listed but refused at this size: the encoder's default profile still plays everywhere.
                    encoder.reset();
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                }
            } else {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoderSurface = encoder.createInputSurface();
            stage = "encoder_start";
            encoder.start();
            encoderStarted = true;
        }

        private void startDecoder(MediaFormat input) throws Exception {
            stage = "decoder_create";
            MediaFormat format = new MediaFormat(input);
            // Rotation is applied by the crop transform; a decoder that also applied it would turn twice.
            format.removeKey(MediaFormat.KEY_ROTATION);
            if (plan.hdr() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Best effort: decoders that can tone-map HDR do so; others keep their own output.
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            decoder = MediaCodec.createByCodecName(plan.decoder());
            stage = "decoder_configure";
            decoder.configure(format, decoderSurface, null, 0);
            stage = "decoder_start";
            decoder.start();
            decoderStarted = true;
        }

        private void loop(MediaExtractor extractor, Renderer renderer, FrameWaiter waiter) throws Exception {
            float[] crop = AmLocalPolicy.crop(plan.width(), plan.height(), plan.rotation());
            float[] stored = new float[16], transform = new float[16];
            long started = SystemClock.elapsedRealtime();
            lastOutputAt = started;
            long origin = Long.MIN_VALUE, lastKept = Long.MIN_VALUE;
            boolean inputDone = false, decoderDone = false, encoderDone = false;
            while (!encoderDone) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
                long now = SystemClock.elapsedRealtime();
                if (now - started > DEADLINE_MS || now - lastOutputAt > STALL_MS) {
                    throw new AmFailure(Status.UNSUPPORTED, "local_transcode_failed", 0, "stage=" + stage + " stalled");
                }
                if (!inputDone) {
                    stage = "decoder_input";
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            // Past the last sample the next read returns -1 and queues the end of stream.
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                encoderDone = drainEncoder(decoderDone);
                if (decoderDone) continue;
                stage = "decoder_output";
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index < 0) continue;
                lastOutputAt = SystemClock.elapsedRealtime();
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                long pts = info.presentationTimeUs;
                if (origin == Long.MIN_VALUE && info.size != 0) origin = pts;
                boolean last = info.size != 0 && pts - origin >= LAST_FRAME_US;
                boolean render = info.size != 0 && !last && pts >= origin && AmLocalPolicy.keep(pts, lastKept);
                decoder.releaseOutputBuffer(index, render);
                if (render) {
                    stage = "frame_wait";
                    if (!waiter.await(2_500)) throw new AmFailure(Status.UNSUPPORTED, "local_transcode_failed", 0, "stage=frame_timeout");
                    stage = "frame_draw";
                    texture.updateTexImage();
                    texture.getTransformMatrix(stored);
                    Matrix.multiplyMM(transform, 0, stored, 0, crop, 0);
                    renderer.draw(transform);
                    egl.present((pts - origin) * 1000);
                    lastKept = pts;
                    progress.accept(pts - origin);
                }
                if (end || last) {
                    // The clip ends here; frames past the contract's length are never shown.
                    stage = "encoder_end";
                    encoder.signalEndOfInputStream();
                    decoderDone = true;
                    inputDone = true;
                }
            }
        }

        /** Moves every finished encoder buffer into the muxer; true once the end of stream came out. */
        private boolean drainEncoder(boolean ending) throws Exception {
            stage = "encoder_output";
            while (true) {
                int index = encoder.dequeueOutputBuffer(info, ending ? TIMEOUT_US : 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IllegalStateException("format_changed_twice");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    continue;
                }
                if (index < 0) continue;
                lastOutputAt = SystemClock.elapsedRealtime();
                ByteBuffer data = encoder.getOutputBuffer(index);
                // Parameter sets already travel in the track format.
                int size = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? 0 : info.size;
                if (size > 0 && data != null) {
                    if (!muxing) throw new IllegalStateException("sample_before_format");
                    data.position(info.offset);
                    data.limit(info.offset + size);
                    muxer.writeSampleData(track, data, info);
                    if ((written += size) > maxBytes) {
                        encoder.releaseOutputBuffer(index, false);
                        throw new AmFailure(Status.UNSUPPORTED, "local_too_large");
                    }
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            }
        }

        private void release() {
            if (decoder != null) {
                if (decoderStarted) try { decoder.stop(); } catch (RuntimeException ignored) { /* releasing anyway */ }
                try { decoder.release(); } catch (RuntimeException ignored) { /* released either way */ }
            }
            if (egl != null) egl.close();
            if (encoder != null) {
                if (encoderStarted) try { encoder.stop(); } catch (RuntimeException ignored) { /* releasing anyway */ }
                try { encoder.release(); } catch (RuntimeException ignored) { /* released either way */ }
            }
            if (encoderSurface != null) encoderSurface.release();
            if (decoderSurface != null) decoderSurface.release();
            if (texture != null) texture.release();
            if (frames != null) frames.quitSafely();
            if (muxer != null) {
                if (muxing) try { muxer.stop(); } catch (RuntimeException ignored) { /* the partial file is discarded */ }
                try { muxer.release(); } catch (RuntimeException ignored) { /* released either way */ }
            }
        }
    }

    /** Signals frames the decoder rendered into the texture; the GL thread waits for each one. */
    private static final class FrameWaiter implements SurfaceTexture.OnFrameAvailableListener {
        private boolean available;

        @Override public synchronized void onFrameAvailable(SurfaceTexture texture) {
            available = true;
            notifyAll();
        }

        synchronized boolean await(long timeoutMs) throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!available) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                wait(left);
            }
            available = false;
            return true;
        }
    }

    /** An EGL context bound to the encoder's input surface on the calling thread. */
    private static final class Egl implements AutoCloseable {
        final EGLDisplay display;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        Egl(Surface window) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("egl_initialize");
            }
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGLExt.EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                close();
                throw new IllegalStateException("egl_config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) { close(); throw new IllegalStateException("egl_context"); }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], window, new int[] { EGL14.EGL_NONE }, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) { close(); throw new IllegalStateException("egl_surface"); }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) { close(); throw new IllegalStateException("egl_current"); }
        }

        void present(long ptsNs) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs);
            if (!EGL14.eglSwapBuffers(display, surface)) throw new IllegalStateException("egl_swap");
        }

        @Override public void close() {
            if (display == EGL14.EGL_NO_DISPLAY) return;
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            surface = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
            EGL14.eglReleaseThread();
            // Android counts initializations per display, so this pairs with ours only.
            EGL14.eglTerminate(display);
        }
    }

    /** One textured quad sampling the decoder's external texture through the crop transform. */
    private static final class Renderer {
        private static final String VERTEX = "uniform mat4 uTexMatrix;\n"
                + "attribute vec4 aPosition;\n"
                + "attribute vec4 aTexCoord;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                + "}\n";
        private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
                + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                + "precision highp float;\n"
                + "#else\n"
                + "precision mediump float;\n"
                + "#endif\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform samplerExternalOES sTexture;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                + "}\n";
        final int texture;
        private final int program, position, coordinate, matrix, side;
        private final FloatBuffer quad;

        Renderer(int side) {
            this.side = side;
            program = link();
            position = GLES20.glGetAttribLocation(program, "aPosition");
            coordinate = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            if (position < 0 || coordinate < 0 || matrix < 0) throw new IllegalStateException("gl_locations");
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            texture = ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            float[] vertices = { -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1 };
            quad = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(vertices).position(0);
            check("gl_setup");
        }

        void draw(float[] transform) {
            GLES20.glViewport(0, 0, side, side);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(position);
            quad.position(2);
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(coordinate);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            check("gl_draw");
        }

        private static int link() {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX), fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) throw new IllegalStateException("gl_link");
            return program;
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException("gl_compile");
            return shader;
        }

        private static void check(String step) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(step + "_" + error);
        }
    }
}
