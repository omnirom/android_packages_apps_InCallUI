package com.android.incallui;

import android.app.Activity;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Handles the call card activity that pops up when a call
 * arrives
 */
public class InCallCardActivity extends Activity {
    public static final String EXTRA_CALLER_ID = "caller_id";
    public static final String EXTRA_CALL_ID = "call_id";

    private static final int SLIDE_IN_DURATION_MS = 500;

    private int mCallId;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.card_call_incoming);

        Bundle extras = getIntent().getExtras();
        mCallId = extras.getInt(EXTRA_CALL_ID);

        // Setup the information of the call
        String caller = getIntent().getExtras().getString(EXTRA_CALLER_ID);
        TextView tv = (TextView) findViewById(R.id.txt_contact_name);
        tv.setText(caller);

        // Setup the call button
        Button answer = (Button) findViewById(R.id.btn_answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallCommandClient.getInstance().answerCall(mCallId);
            }
        });

        // Slide in the dialog
        final LinearLayout vg = (LinearLayout) findViewById(R.id.root);

        vg.setTranslationY(getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height));
        vg.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator()).start();

    }


}

