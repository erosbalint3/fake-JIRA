package com.example.fakejira;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.example.fakejira.models.Feladat;
import com.google.firebase.firestore.FirebaseFirestore;

public class TaskDetailActivity extends AppCompatActivity {

    private TextView taskName;
    private TextView taskDescription;
    private TextView taskPriority;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private Feladat task;

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

    private void acceptTask() {
        final var collectionReference = db.collection("OwnTasks");
        collectionReference.add(this.task)
            .addOnSuccessListener(documentReference -> openDialog());
    }

    private void openDialog() {
        final var intent = new Intent(this.getApplicationContext(), NavigationActivity.class);
        startActivity(intent);
    }
}