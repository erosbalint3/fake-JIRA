package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import com.example.fakejira.models.Feladat;
import com.google.firebase.firestore.FirebaseFirestore;

public class UpdateTaskActivity extends AppCompatActivity {

    private EditText taskName;
    private EditText taskDescription;
    private EditText taskPriority;
    private Button updateTaskButton;
    private Button backButton;
    private Integer taskID;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_task);

        getElementsFromActivity();
    }

    private void getElementsFromActivity() {
        this.taskName = this.findViewById(R.id.taskNameText);
        this.taskDescription = this.findViewById(R.id.taskDescriptionText);
        this.taskPriority = this.findViewById(R.id.taskPriorityText);
        this.updateTaskButton = this.findViewById(R.id.updateTaskButton);
        this.updateTaskButton.setOnClickListener(task -> updateTask());
        this.backButton = this.findViewById(R.id.BackButton);
    }

    private void updateTask() {
        final var taskNameText = this.taskName.getText().toString();
        final var taskDescription = this.taskDescription.getText().toString();
        final var taskPriority = Integer.parseInt(this.taskPriority.getText().toString());
        this.taskID = getIntent().getExtras().getInt("id");
        final var task = new Feladat(taskNameText, taskPriority, taskDescription);
        final var collectionReference = db.collection("AvailableTasks");
        collectionReference.document(task.getTaskID().toString()).set(task);
        redirectToHomeScreen();
    }

    private void redirectToHomeScreen() {
        final var intent = new Intent(this.getApplicationContext(), NavigationActivity.class);
        startActivity(intent);
    }
}