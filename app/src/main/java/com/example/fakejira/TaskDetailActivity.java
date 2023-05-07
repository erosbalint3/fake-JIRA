package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import com.example.fakejira.models.Feladat;
import com.google.firebase.firestore.FirebaseFirestore;

public class TaskDetailActivity extends AppCompatActivity {

    private TextView taskName;
    private TextView taskDescription;
    private TextView taskPriority;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private Feladat task;
    private Button updateTaskButton;
    private Button deleteTaskButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);
        getElementFromActivity();
        setTextValues();
    }

    private void getElementFromActivity() {
        this.taskName = this.findViewById(R.id.TaskNameText);
        this.taskDescription = this.findViewById(R.id.TaskDescriptionText);
        this.taskPriority = this.findViewById(R.id.TaskPriorityText);
        final var acceptTaskButton = this.findViewById(R.id.AcceptTaskButton);
        acceptTaskButton.setOnClickListener(task -> acceptTask());
        this.updateTaskButton = this.findViewById(R.id.updateTaskButton);
        this.updateTaskButton.setOnClickListener(task -> updateTask());
        this.deleteTaskButton = this.findViewById(R.id.DeleteTaskButton);
        this.deleteTaskButton.setOnClickListener(task -> deleteTask());
    }

    private void setTextValues() {
        final var extras =  getIntent().getExtras();
        if (extras != null) {
            this.taskName.setText(extras.getString("taskName"));
            this.taskDescription.setText(extras.getString("taskDescription"));
            this.taskPriority.setText(extras.getString("taskPriority"));
            this.task = new Feladat(
                extras.getString("taskName"),
                Integer.parseInt(extras.getString("taskPriority")),
                extras.getString("taskDescription")
            );
        }
    }

    private void deleteTask() {
        final var collectionReference = db.collection("AvailableTasks");
        final var task = collectionReference.whereEqualTo("taskName", taskName.getText().toString());
        task.get().addOnCompleteListener(task1 -> {
            if (task1.isSuccessful()) {
                task1.getResult().getDocuments().forEach(t -> {
                    t.getReference().delete();
                });
            }
        });
        final var intent = new Intent(this.getApplicationContext(), NavigationActivity.class);
        startActivity(intent,  ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
    }

    private void acceptTask() {
        final var collectionReference = db.collection("OwnTasks");
        collectionReference.add(this.task)
            .addOnSuccessListener(documentReference -> openDialog());
    }

    private void updateTask() {
        final var intent = new Intent(this.getApplicationContext(), UpdateTaskActivity.class);
        final var extras = getIntent().getExtras();
        intent.putExtra("id", extras.getInt("taskID"));
        startActivity(intent,  ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
    }

    private void openDialog() {
        final var intent = new Intent(this.getApplicationContext(), NavigationActivity.class);
        startActivity(intent,  ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
    }
}