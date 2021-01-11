package com.aiyaapp.aiya;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import com.aiyaapp.aiya.cameraTool.AYPreviewView;
import com.aiyaapp.aiya.cameraTool.AYPreviewViewListener;
import com.aiyaapp.aiya.decoderTool.AYMediaCodecDecoder;
import com.aiyaapp.aiya.decoderTool.AYMediaCodecDecoderListener;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoder;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderListener;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFill;
import static com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper.getAvcSupportedFormatInfo;

/**
 * 解码和编码
 */
public class DecoderAndEncoderActivity extends AppCompatActivity implements AYPreviewViewListener, AYMediaCodecDecoderListener, AYMediaCodecEncoderListener {

    private static final String TAG = "DecoderAndEncoder";

    // 用于预览解码画面的surface
    private AYPreviewView surfaceView;
    volatile boolean foreground = false;

    // 音视频硬编码
    String videoPath;
    volatile AYMediaCodecEncoder encoder;
    volatile boolean videoCodecConfigResult = false;
    volatile boolean audioCodecConfigResult = false;

    // 音视频硬解码
    volatile AYMediaCodecDecoder decoder;
    volatile boolean videoDecoderEOS = false;
    volatile boolean audioDecoderEOS = false;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_decoder_and_encoder);

        surfaceView = findViewById(R.id.decoder_and_encoder_preview);
        surfaceView.setListener(this);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDecoder();
            }
        });
    }

    @Override
    public void createGLEnvironment() {
        foreground = true;
    }

    @Override
    public void destroyGLEnvironment() {
        foreground = false;

        if (decoder != null) {
            decoder.abortDecoder();
        }

        if (encoder != null) {
            encoder.finish();
        }
    }

    private void startDecoder() {

        // 设置视频编辑完成后路径
        if (getExternalCacheDir() != null) {
            videoPath = getExternalCacheDir().getAbsolutePath();
        } else {
            videoPath = getCacheDir().getAbsolutePath();
        }
        videoPath = videoPath + File.separator + UUID.randomUUID().toString().replace("-", "") + ".mp4";

        // 启动编码, 每次都是先设置
        encoder = new AYMediaCodecEncoder(videoPath);
        encoder.setContentMode(kAYGPUImageScaleAspectFill);
        encoder.setMediaCodecEncoderListener(this);

        // 启动解码
        try {
            AssetFileDescriptor masterFd = getResources().openRawResourceFd(R.raw.test);
            decoder = new AYMediaCodecDecoder(masterFd);
            decoder.setDecoderListener(this);
            decoder.configCodec(surfaceView.eglContext);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void decoderOutputVideoTrackFormat(MediaFormat format) {
        encoder.prepareForAddingTrack(format);
    }

    @Override
    public void decoderOutputAudioTrackFormat(MediaFormat format) {
        encoder.prepareForAddingTrack(format);
    }

    @Override
    public void decoderOutputVideoFormat(MediaFormat format) {

        // 图像编码参数
        int height = format.getInteger(MediaFormat.KEY_WIDTH); // 视频编码时图像旋转了90度
        int width = format.getInteger(MediaFormat.KEY_HEIGHT);
        int bitRate = 1000000; // 码率: 1Mbps
        int fps = 30; // 帧率: 30
        int iFrameInterval = 1; // GOP: 30

        // 编码器信息
        AYMediaCodecEncoderHelper.CodecInfo codecInfo = getAvcSupportedFormatInfo();
        if (codecInfo == null) {
            Log.d(TAG, "不支持硬编码");
            return;
        }

        // 设置给编码器的参数不能超过其最大值
        if (width > codecInfo.maxWidth) {
            width = codecInfo.maxWidth;
        }
        if (height > codecInfo.maxHeight) {
            height = codecInfo.maxHeight;
        }
        if (bitRate > codecInfo.bitRate) {
            bitRate = codecInfo.bitRate;
        }
        if (fps > codecInfo.fps) {
            fps = codecInfo.fps;
        }

        Log.d(TAG, "开始视频编码，初始化参数 : " + "width = " + width + "height = " + height + "bitRate = " + bitRate
                + "fps = " + fps + "IFrameInterval = " + iFrameInterval);

        int finalWidth = width;
        int finalHeight = height;
        int finalBitRate = bitRate;
        int finalFps = fps;

        videoCodecConfigResult = encoder.configureVideoCodec(surfaceView.eglContext, finalWidth, finalHeight, finalBitRate, finalFps, iFrameInterval);

        if (videoCodecConfigResult && audioCodecConfigResult) {
            decoder.start();
            encoder.start();
        }
    }

    @Override
    public void decoderOutputAudioFormat(MediaFormat format) {
        // 音频编码参数
        int audioBitRate = 128000; // 码率: 128kbps
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); // 采样率
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT); // 通道数

        Log.d(TAG, "开始音频编码，初始化参数 : " + "sampleRate = " + sampleRate + " channelCount = " + channelCount);

        audioCodecConfigResult = encoder.configureAudioCodec(audioBitRate, sampleRate, channelCount);

        if (videoCodecConfigResult && audioCodecConfigResult) {
            decoder.start();
            encoder.start();
        }
    }

    @Override
    public void decoderVideoOutput(int texture, int width, int height, long timestamp) {

        // 渲染到surfaceView
        surfaceView.render(texture, width, height);

        // 编码器视频编码
        encoder.writeImageTexture(texture, width, height, timestamp);
    }

    @Override
    public void decoderAudioOutput(ByteBuffer byteBuffer, long timestamp) {

        // 编码器音频编码
        encoder.writePCMByteBuffer(byteBuffer, timestamp);
    }

    @Override
    public void decoderVideoEOS() {
        videoDecoderEOS = true;
        if (videoDecoderEOS && audioDecoderEOS) {
            encoder.finish();

            if (new File(videoPath).exists()) {
                showVideo();
            }
        }
    }

    @Override
    public void decoderAudioEOS() {
        audioDecoderEOS = true;
        if (videoDecoderEOS && audioDecoderEOS) {
            encoder.finish();

            showVideo();
        }
    }

    @Override
    public void encoderOutputVideoFormat(MediaFormat format) {
        videoCodecConfigResult = true;
    }

    @Override
    public void encoderOutputAudioFormat(MediaFormat format) {
        audioCodecConfigResult = true;
    }

    public void showVideo() {
        if (new File(videoPath).exists()) {
            Log.d(TAG, "文件保存路径: " + videoPath);
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Uri contentUri = FileProvider.getUriForFile(getBaseContext(), "com.aiyaapp.aiya.test.fileprovider", new File(videoPath));
                    intent.setDataAndType(contentUri, "video/mp4");
                } else {
                    intent.setDataAndType(Uri.fromFile(new File(videoPath)), "video/mp4");
                }
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}