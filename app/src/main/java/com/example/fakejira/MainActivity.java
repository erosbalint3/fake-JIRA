package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import com.example.fakejira.ui.Profile.profileFragment;
import com.google.firebase.auth.FirebaseAuth;

public class MainActivity extends AppCompatActivity {

    private Button availableTasksButton;
    private Button ownTasksButton;
    private Button notificationsButton;
    private Button settingsButton;
    private Button profileButton;
    private Button logoutButton;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getElementsFromActivity();
        setOnClickMethods();
    }

    private void getElementsFromActivity() {
        this.availableTasksButton = this.findViewById(R.id.elerhetoFeladatokButton);
        this.ownTasksButton = this.findViewById(R.id.sajatFeladatokButton);
        this.notificationsButton = this.findViewById(R.id.notificationsButton);
        this.settingsButton = this.findViewById(R.id.beallitasokButton);
        this.profileButton = this.findViewById(R.id.profileButton);
        this.logoutButton = this.findViewById(R.id.logoutButton);
    }

    private void setOnClickMethods() {
        this.notificationsButton.setOnClickListener(view -> switchActivities(new notificationsActivity()));
        this.settingsButton.setOnClickListener(view -> switchActivities(new settingsActivity()));
        this.logoutButton.setOnClickListener(view -> logout());
    }

    private void logout() {
        FirebaseAuth.getInstance().signOut();
    }

    private void switchActivities(final Activity activity) {
        final Intent intent = new Intent(this.getApplicationContext(), activity.getClass());
        startActivity(intent);
    }
}