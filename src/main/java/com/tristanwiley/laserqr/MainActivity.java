package com.tristanwiley.laserqr;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.barcode.Barcode;
import com.hound.android.fd.Houndify;

public class MainActivity extends Activity {

    private static final int RC_BARCODE_CAPTURE = 1;
    private static final String TAG = "BarcodeMain";
    Button createGame, submitButton;
    EditText gameCode;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Normally you'd only have to do this once in your Application#onCreate
        Houndify.get(this).setClientId( Constants.CLIENT_ID );
        Houndify.get(this).setClientKey(Constants.CLIENT_KEY);
        Houndify.get(this).setRequestInfoFactory(StatefulRequestInfoFactory.get(this));

        createGame = (Button) findViewById(R.id.createGame);
        gameCode = (EditText) findViewById(R.id.gameCode);
        submitButton = (Button) findViewById(R.id.submitButton);
        submitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, QRScanner.class);
                i.putExtra("EXTRA_GAME", gameCode.getText().toString());
                Log.e("onClick", gameCode.getText().toString());
                startActivity(i);
            }
        });

    }

}
