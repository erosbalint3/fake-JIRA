package com.example.fakejira.ui.AvailableTasks;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.example.fakejira.R;
import com.example.fakejira.TaskDetailActivity;
import com.example.fakejira.databinding.FragmentHomeBinding;
import com.example.fakejira.models.Feladat;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    private List<Feladat> feladatok = new ArrayList<>();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private FloatingActionButton addTaskButton;
    private ListView lista;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        HomeViewModel homeViewModel =
                new ViewModelProvider(this).get(HomeViewModel.class);
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        setActivityLayouts();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        addTasksToListView();
    }

    private void setActivityLayouts() {
        lista = binding.getRoot().findViewById(R.id.list_view);
        this.addTaskButton = binding.getRoot().findViewById(R.id.floatingActionButton);
        this.addTaskButton.setOnClickListener(task -> goToTaskAdding());
    }

    private void goToTaskAdding() {
        final var animation = AnimationUtils.loadAnimation(getContext(), R.anim.blink);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_nav_home_to_nav_add_task);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        this.addTaskButton.startAnimation(animation);
        //Navigation.findNavController(binding.getRoot()).navigate(R.id.action_nav_home_to_nav_add_task);
    }

    private void addTasksToListView() {
        final var collectionReference = db.collection("AvailableTasks");
        this.feladatok = new ArrayList<>();
        collectionReference.get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                final var taskList = new ArrayList<>();
                for (final var document : task.getResult()) {
                    final var t = document.getData();
                    final var tmp = Objects.requireNonNull(t.get("taskName")).toString();
                    if (!taskList.contains(tmp)) {
                        taskList.add(tmp);
                        this.feladatok.add(new Feladat(
                                Objects.requireNonNull(t.get("taskName")).toString(),
                                Integer.parseInt(Objects.requireNonNull(t.get("taskPriority")).toString()),
                                Objects.requireNonNull(t.get("taskDescription")).toString()
                        ));
                    }
                }
                final var adapter = new ArrayAdapter<>(
                        HomeFragment.this.getContext(),
                        android.R.layout.simple_list_item_1,
                        taskList
                ){
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        TextView textView = view.findViewById(android.R.id.text1);
                        textView.setTextColor(getResources().getColor(R.color.white));
                        textView.setOnClickListener(v -> openDialog(textView.getText().toString()));
                        return view;
                    }
                };
                lista.setAdapter(adapter);
            }
        });
    }

    private void openDialog(final String taskName) {
        final var collectionReference = db.collection("AvailableTasks");
        final var task = collectionReference.whereEqualTo("taskName", taskName);

        task.get().addOnCompleteListener(task1 -> {
            if (task1.isSuccessful()) {
                final var tasks = task1.getResult().getDocuments().get(0).getData();
                final var intent = new Intent(this.getContext(), TaskDetailActivity.class);
                assert tasks != null;
                intent.putExtra(
                        "taskName",
                        Objects.requireNonNull(tasks.get("taskName")).toString()
                );
                intent.putExtra(
                        "taskDescription",
                        Objects.requireNonNull(tasks.get("taskDescription")).toString()
                );
                intent.putExtra(
                        "taskPriority",
                        Objects.requireNonNull(tasks.get("taskPriority")).toString()
                );
                intent.putExtra(
                        "taskID",
                        Integer.parseInt(Objects.requireNonNull(tasks.get("taskID")).toString())
                );
                startActivity(intent,  ActivityOptions.makeSceneTransitionAnimation(getActivity()).toBundle());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}