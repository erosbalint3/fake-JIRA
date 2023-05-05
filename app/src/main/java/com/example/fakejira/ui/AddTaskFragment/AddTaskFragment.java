package com.example.fakejira.ui.AddTaskFragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.example.fakejira.R;
import com.example.fakejira.databinding.FragmentAddtaskBinding;
import com.example.fakejira.models.Feladat;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class AddTaskFragment extends Fragment {

    private FragmentAddtaskBinding binding;
    private Button addTaskButton;
    private EditText taskName;
    private EditText taskDescription;
    private EditText taskPriority;
    private View root;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public View onCreateView(
        @NonNull LayoutInflater inflater,
        ViewGroup container,
        Bundle savedInstanceState
    ) {
        AddTaskViewModel profileViewModel =
            new ViewModelProvider(this).get(AddTaskViewModel.class);
        binding = FragmentAddtaskBinding.inflate(inflater, container, false);
        root = binding.getRoot();
        setFragmentElements();
        return root;
    }

    private void setFragmentElements() {
        this.addTaskButton = root.findViewById(R.id.updateTaskButton);
        this.taskName = root.findViewById(R.id.taskNameText);
        this.taskDescription = root.findViewById(R.id.taskDescriptionText);
        this.taskPriority = root.findViewById(R.id.taskPriorityText);
        this.addTaskButton.setOnClickListener(task -> addTask());
    }

    private void addTask() {
        final var task = new Feladat(
            this.taskName.getText().toString(),
            Integer.parseInt(this.taskPriority.getText().toString()),
            this.taskDescription.getText().toString()
        );
        final var collectionReference = db.collection("AvailableTasks");
        if (collectionContainsTask(task) != true) {
            collectionReference.document(task.getTaskID().toString()).set(task).addOnCompleteListener(task1 -> {
                 Log.println(Log.INFO, "AddTaskFragment", "Successfully created task in database!");
            });
        }
        redirectBackToAvailableTasks();
    }

    private void redirectBackToAvailableTasks() {
        Navigation.findNavController(root).navigate(R.id.action_nav_add_task_to_nav_home);
    }

    private boolean collectionContainsTask(final Feladat feladat) {
        AtomicBoolean contains = new AtomicBoolean(false);
        final var collectionReference = db.collection("AvailableTasks");
        collectionReference.get().addOnCompleteListener(task -> {
            if (task.isSuccessful()){
                for (final var document : task.getResult()) {
                    final var t = document.getData();
                    if (Objects.equals(feladat.getTaskName() + " ", Objects.requireNonNull(t.get("taskName")).toString())) {
                        contains.set(true);
                        break;
                    }
                }
            }
        });
        return contains.get();
    }

}
