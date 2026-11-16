package com.example.inspireface_example;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.inspireface_example.face.FaceRepository;
import com.example.inspireface_example.permission.CameraPermissionCoordinator;
import com.example.inspireface_example.ui.UiActivity;
import com.example.inspireface_example.view.AutoIdFaceAnalyzer;
import com.example.inspireface_example.view.CameraPreviewController;
import com.example.inspireface_example.view.FaceEngine;
import com.example.inspireface_example.view.FaceTrackingGlView;
import com.example.inspireface_example.view.TrackIdOverlayView;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live multi-face demo that shows persistent gallery ids (P001, P002, ...) on every
 * box instead of the short-lived SDK track id. First sight auto-enrolls; after a
 * person fully leaves the frame and returns, the same id is reused from the gallery.
 *
 * <p>Records created here are ordinary {@link FaceRepository} entries, so they are
 * visible (with their numeric ids) in the existing "Face management" screen.
 */
public final class AutoIdActivity extends UiActivity {

    private final ExecutorService sdkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    private FaceRepository repository;
    private CameraPermissionCoordinator cameraPermission;
    private CameraPreviewController cameraController;
    private AutoIdFaceAnalyzer autoIdAnalyzer;

    private PreviewView preview;
    private FaceTrackingGlView glOverlay;
    private TrackIdOverlayView labelOverlay;
    private TextView statusView;
    private TextView galleryView;
    private TextView labelsView;
    private TextView eventView;
    private TextView currentModel;
    private View loadingIndicator;

    private volatile boolean destroyed;
    private boolean engineReady;
    private boolean cameraStarting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_id);
        bindViews();

        cameraPermission = new CameraPermissionCoordinator(this,
                new CameraPermissionCoordinator.Listener() {
                    @Override
                    public void onCameraPermissionGranted() {
                        startCamera();
                    }

                    @Override
                    public void onCameraPermissionBlocked(boolean requiresSettings) {
                        setStatus(requiresSettings
                                ? R.string.camera_permission_settings_hint
                                : R.string.camera_permission_retry_hint);
                    }
                });
        cameraPermission.bindRecoveryButton(findViewById(R.id.btnAutoIdPermissionAction));

        currentModel.setText(getString(R.string.current_model,
                FaceModelPrefs.get(this).sdkName()));
        findViewById(R.id.btnAutoIdBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFlipAutoIdCamera).setOnClickListener(v -> flipCamera());
        findViewById(R.id.btnResetAutoIdSession).setOnClickListener(v -> {
            if (autoIdAnalyzer != null) {
                autoIdAnalyzer.resetSession();
                setStatus(R.string.auto_id_status_reset);
                Toast.makeText(this, R.string.auto_id_reset_toast, Toast.LENGTH_SHORT).show();
            }
        });

        repository = new FaceRepository(this, FaceModelPrefs.get(this));
        setStatus(R.string.auto_id_status_initializing);
        showLoading(true);
        sdkExecutor.execute(this::initialize);
    }

    private void bindViews() {
        preview = findViewById(R.id.autoIdPreview);
        glOverlay = findViewById(R.id.autoIdGlOverlay);
        labelOverlay = findViewById(R.id.autoIdLabelOverlay);
        statusView = findViewById(R.id.autoIdStatus);
        galleryView = findViewById(R.id.autoIdGalleryCount);
        labelsView = findViewById(R.id.autoIdLabels);
        eventView = findViewById(R.id.autoIdEvent);
        currentModel = findViewById(R.id.autoIdCurrentModel);
        loadingIndicator = findViewById(R.id.autoIdLoading);
    }

    /** Runs on {@link #sdkExecutor}, after GlobalLaunch, mirroring FaceRecognitionActivity. */
    private void initialize() {
        boolean ready = FaceEngine.ensureLaunched(this);
        if (ready) {
            ready = repository.open();
        }
        if (!ready) {
            repository.close();
        }
        boolean finalReady = ready;
        runOnUiThread(() -> {
            engineReady = finalReady;
            showLoading(false);
            if (finalReady) {
                setStatus(R.string.auto_id_status_ready);
                if (cameraPermission.hasPermission()) {
                    startCamera();
                } else {
                    cameraPermission.requestAccess();
                }
            } else {
                setStatus(R.string.auto_id_status_error);
            }
        });
    }

    private void startCamera() {
        if (destroyed || !engineReady || cameraController != null || cameraStarting) {
            return;
        }
        if (!cameraPermission.hasPermission()) {
            cameraPermission.requestAccess();
            return;
        }
        cameraStarting = true;
        AutoIdFaceAnalyzer analyzer = new AutoIdFaceAnalyzer(
                glOverlay, labelOverlay, repository,
                new AutoIdFaceAnalyzer.Listener() {
                    @Override
                    public void onSessionReady() {
                        postUi(() -> {
                            cameraStarting = false;
                            showLoading(false);
                            setStatus(R.string.auto_id_status_ready);
                        });
                    }

                    @Override
                    public void onSessionError() {
                        postUi(() -> {
                            cameraStarting = false;
                            showLoading(false);
                            setStatus(R.string.auto_id_status_error);
                        });
                    }

                    @Override
                    public void onUpdate(int faceCount, long gallerySize,
                                         String[] labels, String event) {
                        postUi(() -> renderUpdate(faceCount, gallerySize, labels, event));
                    }
                });
        autoIdAnalyzer = analyzer;
        cameraController = new CameraPreviewController(
                this, this, preview, analysisExecutor, analyzer,
                new CameraPreviewController.Listener() {
                    @Override
                    public void onCameraReady(boolean frontCamera) {
                        analyzer.setMirrored(frontCamera);
                    }

                    @Override
                    public void onLensChanged(boolean frontCamera) {
                        analyzer.setMirrored(frontCamera);
                    }

                    @Override
                    public void onCameraError(@StringRes int messageRes) {
                        postUi(() -> {
                            cameraStarting = false;
                            showLoading(false);
                            statusView.setText(messageRes);
                            statusView.setTextColor(
                                    ContextCompat.getColor(AutoIdActivity.this,
                                            R.color.liveness_fail));
                        });
                    }
                });
        cameraController.start(true);
    }

    private void renderUpdate(int faceCount, long gallerySize,
                              String[] labels, String event) {
        galleryView.setText(getString(R.string.auto_id_gallery_count, gallerySize, faceCount));
        if (labels == null || labels.length == 0) {
            labelsView.setText(getString(R.string.auto_id_labels, "—"));
        } else {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) {
                    joined.append("  ");
                }
                joined.append(labels[i]);
            }
            labelsView.setText(getString(R.string.auto_id_labels, joined.toString()));
        }
        eventView.setText(getString(R.string.auto_id_event, event));
    }

    private void flipCamera() {
        if (cameraController == null || !cameraController.flipCamera()) {
            Toast.makeText(this, R.string.msg_camera_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(@StringRes int res) {
        statusView.setText(res);
        statusView.setTextColor(ContextCompat.getColor(this, R.color.home_text_primary));
    }

    private void showLoading(boolean loading) {
        loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing()) {
                action.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraPermission.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        glOverlay.onResume();
    }

    @Override
    protected void onStop() {
        glOverlay.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        engineReady = false;
        cameraPermission.close();
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }
        AutoIdFaceAnalyzer toRelease = autoIdAnalyzer;
        autoIdAnalyzer = null;
        if (toRelease != null) {
            analysisExecutor.execute(toRelease::release);
        }
        // Queued behind release() on the same executor: no in-flight frame can touch
        // FeatureHub after it is disabled.
        analysisExecutor.execute(() -> repository.close());
        analysisExecutor.shutdown();
        sdkExecutor.shutdown();
        super.onDestroy();
    }
}
