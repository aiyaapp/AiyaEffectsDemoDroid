package com.aiyaapp.aiya;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;

import com.aiyaapp.aiya.gpuImage.AYGLProgram;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.gpuImage.AYGPUImageEGLContext;
import com.aiyaapp.aiya.gpuImage.AYGPUImageFilter;
import com.aiyaapp.aiya.gpuImage.AYGPUImageFramebuffer;

import java.io.IOException;
import java.nio.Buffer;

import static android.opengl.GLES20.*;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageNoRotation;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.needExchangeWidthAndHeightWithRotation;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.textureCoordinatesForRotation;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageEGLContext.syncRunOnRenderThread;

public class AYCameraPreviewWrap implements SurfaceTexture.OnFrameAvailableListener {
    public static final String TAG = "AYCameraPreviewWrap";

    public static final String kAYOESTextureFragmentShader = "" +
            "#extension GL_OES_EGL_image_external : require\n" +
            "\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "\n" +
            "uniform samplerExternalOES inputImageTexture;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(inputImageTexture, textureCoordinate);\n" +
            "}";

    private Camera mCamera;

    private AYGPUImageEGLContext eglContext = new AYGPUImageEGLContext();

    private SurfaceTexture surfaceTexture;

    private int oesTexture;

    private AYCameraPreviewListener previewListener;

    private AYGPUImageFramebuffer outputFramebuffer;

    private AYGPUImageConstants.AYGPUImageRotationMode rotateMode = kAYGPUImageNoRotation;

    private int inputWidth;
    private int inputHeight;

    private AYGLProgram filterProgram;

    private int filterPositionAttribute, filterTextureCoordinateAttribute;
    private int filterInputTextureUniform;

    private Buffer imageVertices = AYGPUImageConstants.floatArrayToBuffer(AYGPUImageConstants.imageVertices);
    private Buffer textureCoordinates = AYGPUImageConstants.floatArrayToBuffer(AYGPUImageConstants.noRotationTextureCoordinates);

    private AYCameraPreviewWrap() {}

    public AYCameraPreviewWrap(Camera camera) {
        mCamera = camera;
        init();
    }

    public void setCamera(Camera camera){
        mCamera = camera;
        init();
    }

    private void init() {
        syncRunOnRenderThread(new Runnable() {
            @Override
            public void run() {
                eglContext.initEGLWindow(new SurfaceTexture(0));

                oesTexture = createOESTextureID();
                surfaceTexture = new SurfaceTexture(oesTexture);
                surfaceTexture.setOnFrameAvailableListener(AYCameraPreviewWrap.this);

                filterProgram = new AYGLProgram(AYGPUImageFilter.kAYGPUImageVertexShaderString, kAYOESTextureFragmentShader);
                filterProgram.link();

                filterPositionAttribute = filterProgram.attributeIndex("position");
                filterTextureCoordinateAttribute = filterProgram.attributeIndex("inputTextureCoordinate");
                filterInputTextureUniform = filterProgram.uniformIndex("inputImageTexture");
                filterProgram.use();
            }
        });
    }

    public void startPreview() {
        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ignored) {
        }

        Camera.Size s = mCamera.getParameters().getPreviewSize();
        inputWidth = s.width;
        inputHeight = s.height;

        setRotateMode(rotateMode);

        mCamera.startPreview();
   }

   public void stopPreview() {
       destroy();
       mCamera.stopPreview();
   }
   
    @Override
    public void onFrameAvailable(final SurfaceTexture surfaceTexture) {
        syncRunOnRenderThread(new Runnable() {
            @Override
            public void run() {
                eglContext.makeCurrent();

                surfaceTexture.updateTexImage();

                // 因为在shader中处理oes纹理需要使用到扩展类型, 必须要先转换为普通纹理再传给下一级
                renderToFramebuffer(oesTexture);

                if (previewListener != null) {
                    previewListener.cameraVideoOutput(outputFramebuffer.texture[0], inputWidth, inputHeight, surfaceTexture.getTimestamp());
                }
            }
        });
    }

    private void renderToFramebuffer(int oesTexture) {

        filterProgram.use();

        if (outputFramebuffer != null) {
            if (inputWidth != outputFramebuffer.width || inputHeight != outputFramebuffer.height) {
                outputFramebuffer.destroy();
                outputFramebuffer = null;
            }
        }

        if (outputFramebuffer == null) {
            outputFramebuffer = new AYGPUImageFramebuffer(inputWidth, inputHeight);
        }

        outputFramebuffer.activateFramebuffer();

        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);

        glUniform1i(filterInputTextureUniform, 2);

        glEnableVertexAttribArray(filterPositionAttribute);
        glEnableVertexAttribArray(filterTextureCoordinateAttribute);

        glVertexAttribPointer(filterPositionAttribute, 2, GL_FLOAT, false, 0, imageVertices);
        glVertexAttribPointer(filterTextureCoordinateAttribute, 2, GL_FLOAT, false, 0, AYGPUImageConstants.floatArrayToBuffer(textureCoordinatesForRotation(rotateMode)));

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(filterPositionAttribute);
        glDisableVertexAttribArray(filterTextureCoordinateAttribute);
    }

    public void setPreviewListener(AYCameraPreviewListener previewListener) {
        this.previewListener = previewListener;
    }

    public void setRotateMode(AYGPUImageConstants.AYGPUImageRotationMode rotateMode) {
        this.rotateMode = rotateMode;

        if (needExchangeWidthAndHeightWithRotation(rotateMode)) {
            int temp = inputWidth;
            inputWidth = inputHeight;
            inputHeight = temp;
        }
    }

    private int createOESTextureID() {
        int[] texture = new int[1];
        glGenTextures(1, texture, 0);
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_TEXTURE_MIN_FILTER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_TEXTURE_MAG_FILTER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_S);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_T);

        return texture[0];
    }

    public void destroy() {
        syncRunOnRenderThread(new Runnable() {
            @Override
            public void run() {

                filterProgram.destroy();

                if (outputFramebuffer != null) {
                    outputFramebuffer.destroy();
                }

                eglContext.destroyEGLWindow();
            }
        });
    }
}
