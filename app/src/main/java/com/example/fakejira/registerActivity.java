package com.example.fakejira;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class registerActivity extends AppCompatActivity {

    private final String TAG = "Register Activity";

    private FirebaseAuth mAuth;
    private Button registerButton;
    private EditText usernameText;
    private EditText emailText;
    private EditText password;
    private EditText confirmPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        this.mAuth = FirebaseAuth.getInstance();
        this.getElementsFromLayout();

        this.setOnClickListeners();
    }

    private void getElementsFromLayout() {
        this.registerButton = findViewById(R.id.registerUserButton);
        this.usernameText = findViewById(R.id.usernameText);
        this.emailText = findViewById(R.id.registerEmailText);
        this.password = findViewById(R.id.registerPasswordText);
        this.confirmPassword = findViewById(R.id.registerConfirmPasswordText);
    }

    private void setOnClickListeners() {
        this.registerButton.setOnClickListener(view -> validateFields());
    }

    private void registerUser() {
        final String email = this.emailText.getText().toString();
        final String password = this.password.getText().toString();
        this.mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "createUserWithEmail:success");
                            final FirebaseUser user = mAuth.getCurrentUser();
                            updateUI(user);
                        } else {
                            Log.w(TAG, "createUserWithEmail:failure", task.getException());
                            Toast.makeText(registerActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                            updateUI(null);
                        }
                    }
                });
    }

    private boolean validateEmailWithRegex(final String email) {
        final Pattern pattern = Pattern.compile("[A-Za-z0-9]+@[A-Za-z0-9]+\\.[A-Za-z0-9]+", Pattern.CASE_INSENSITIVE);
        final Matcher matcher = pattern.matcher(email);
        return matcher.matches();
    }

    private boolean validatePasswordWithRegex(final String password) {
        return true;
    }

    private void validateFields() {
        final String username = this.usernameText.getText().toString();
        final String email = this.emailText.getText().toString();
        final String password = this.password.getText().toString();
        final String confirmPassword = this.confirmPassword.getText().toString();
        if (username.length() < 4) {
            Toast.makeText(registerActivity.this, "Username must be at least 4 characters long",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (email.length() == 0 || !validateEmailWithRegex(email)) {
            Toast.makeText(registerActivity.this, "Email must not be null, and must be in this format: xxxxx@xxxxx.xxxx",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 8 || !validatePasswordWithRegex(password)) {
            Toast.makeText(registerActivity.this, "Password must be at least 8 characters long and must contain at least one uppercase letter, a number and a special character",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        this.registerUser();
    }

    private void updateUI(final FirebaseUser user) {
        final Intent loginIntent = new Intent(getApplicationContext(), LoginActivity.class);
        startActivity(loginIntent);
    }

}