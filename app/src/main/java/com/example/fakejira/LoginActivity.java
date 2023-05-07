package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private Button registerButton;
    private Button loginButton;
    private EditText emailText;
    private EditText passwordText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        this.mAuth = FirebaseAuth.getInstance();
        this.registerButton = findViewById(R.id.registerFromLoginButton);
        this.loginButton = findViewById(R.id.loginButton);
        this.emailText = findViewById(R.id.emailText);
        this.passwordText = findViewById(R.id.passwordText);

        this.setOnClickMethods();
    }

    @Override
    public void onStart() {
        super.onStart();
        FirebaseUser currentUser = this.mAuth.getCurrentUser();
        if (currentUser != null) {
            reload();
        }
    }

    private void setOnClickMethods() {
        this.registerButton.setOnClickListener(view -> onRegister());
        this.loginButton.setOnClickListener(view -> onLogin());
    }

    private void onRegister() {
        final Intent registerIntent = new Intent(getApplicationContext(), registerActivity.class);
        startActivity(registerIntent);
    }

    private void onLogin() {
        final var email = this.emailText.getText().toString();
        final var password = this.passwordText.getText().toString();

        this.mAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener(t -> {
            Log.println(Log.INFO, "LoginActivity", "Successful signin!");
            reload();
        });
    }

    private void reload() {
        final Intent homeScreenIntent = new Intent(getApplicationContext(), NavigationActivity.class);
        startActivity(homeScreenIntent);
    }
}