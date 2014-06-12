package com.android.incallui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.services.telephony.common.CallIdentification;
import com.android.services.telephony.common.Call;

/**
 * Handles the call card activity that pops up when a call
 * arrives
 */
public class InCallCardActivity extends Activity implements GlowPadWrapper.AnswerListener {
    private static final int SLIDE_IN_DURATION_MS = 500;

    private TextView mNameTextView;
    private ImageView mContactImage;
    private Handler mHandler = new Handler();
    private Call mCall;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        setContentView(R.layout.card_call_incoming);

        InCallPresenter.getInstance().setCardActivity(this);

        final CallList calls = CallList.getInstance();
        mCall = calls.getIncomingCall();

        final CallIdentification identification = mCall.getIdentification();

        // Setup the fields to show the information of the call
        mNameTextView = (TextView) findViewById(R.id.txt_contact_name);
        mContactImage = (ImageView) findViewById(R.id.img_contact);

        ImageButton fullscreenUI = (ImageButton) findViewById(R.id.fullscreen_ui);
        fullscreenUI.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // start fullscreen ui
                InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, false);
                finish();
            }
        });

        GlowPadWrapper glowpad = (GlowPadWrapper) findViewById(R.id.glow_pad_view);
        // Answer or Decline.
        glowpad.setTargetResources(R.array.incoming_call_widget_2way_targets);
        glowpad.setTargetDescriptionsResourceId(
                R.array.incoming_call_widget_2way_target_descriptions);
        glowpad.setDirectionDescriptionsResourceId(
                R.array.incoming_call_widget_2way_direction_descriptions);
        glowpad.reset(false);

        glowpad.setAnswerListener(this);
        glowpad.startPing();

        // Slide in the dialog
        final LinearLayout vg = (LinearLayout) findViewById(R.id.root);

        vg.setTranslationY(getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height));
        vg.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator()).start();

        // Lookup contact info - delay it a little bit the slide-in animation can be disturbed
        // and we want it to look smooth
        mHandler.postDelayed(new Runnable() {
            public void run() {
                startContactInfoSearch(identification);
            }
        }, SLIDE_IN_DURATION_MS/2);
    }

    @Override
    public void onBackPressed() {
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, true);
        }
        super.onBackPressed();
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls.
     */
    private void startContactInfoSearch(final CallIdentification identification) {
        final ContactInfoCache cache = ContactInfoCache.getInstance(InCallCardActivity.this);

        cache.findInfo(identification, true, new ContactInfoCacheCallback() {
                @Override
                public void onContactInfoComplete(int callId, ContactCacheEntry entry) {
                    mNameTextView.setText(entry.name == null ? entry.number : entry.name);
                    if (entry.personUri != null) {
                        CallerInfoUtils.sendViewNotification(InCallCardActivity.this, entry.personUri);
                    }
                }

                @Override
                public void onImageLoadComplete(int callId, ContactCacheEntry entry) {
                    if (entry.photo != null) {
                        Drawable current = mContactImage.getDrawable();
                        int contactHeight = getResources().getDimensionPixelSize(R.dimen.incoming_call_card_contact_height);
                        Drawable photo = resize(entry.photo, contactHeight);
                        AnimationUtils.startCrossFade(mContactImage, current, photo);
                    }
                }
            });
    }

    @Override
    public void onAnswer() {
        InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, false);
        CallCommandClient.getInstance().answerCall(mCall.getCallId());
        finish();
    }

    @Override
    public void onDecline() {
        CallCommandClient.getInstance().rejectCall(mCall, false, null);
        finish();
    }

    @Override
    public void onText() {
        // should not happen
        finish();
    }

    private BitmapDrawable resize(Drawable image, int dpiSize) {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(dpiSize * density);

        Bitmap b = ((BitmapDrawable) image).getBitmap();

        Bitmap bmResult = Bitmap.createBitmap(size, size,
                Bitmap.Config.ARGB_8888);
        Canvas tempCanvas = new Canvas(bmResult);

        Bitmap bitmapResized = Bitmap.createScaledBitmap(b, size,
                size, true);
        tempCanvas.drawBitmap(bitmapResized, 0, 0, null);
        return new BitmapDrawable(getResources(), bmResult);
    }
}

