package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.example.fakejira.ui.AvailableTasks.HomeFragment;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private Button registerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        this.mAuth = FirebaseAuth.getInstance();
        this.registerButton = findViewById(R.id.registerFromLoginButton);

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
    }

    private void onRegister() {
        final Intent registerIntent = new Intent(getApplicationContext(), registerActivity.class);
        startActivity(registerIntent);
    }

    private void reload() {
        final Intent homeScreenIntent = new Intent(getApplicationContext(), NavigationActivity.class);
        startActivity(homeScreenIntent);
        finish();
    }
}