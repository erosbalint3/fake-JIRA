package com.example.fakejira.ui.gallery;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.fakejira.R;
import com.example.fakejira.databinding.FragmentOwntasksBinding;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Objects;

public class OwnTasksFragment extends Fragment {

    private FragmentOwntasksBinding binding;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private ListView lista;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        OwnTaskViewModel ownTaskViewModel =
                new ViewModelProvider(this).get(OwnTaskViewModel.class);

        binding = FragmentOwntasksBinding.inflate(inflater, container, false);

        setActivityLayouts();
        addTasksToListView();

        return binding.getRoot();
    }

    private void setActivityLayouts() {
        lista = binding.getRoot().findViewById(R.id.sajatFeladatLista);
    }

    private void addTasksToListView() {
        final var collectionReference = db.collection("OwnTasks");
        collectionReference.get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                final var taskList = new ArrayList<>();
                for (final var document : task.getResult()) {
                    final var t = document.getData();
                    final var tmp = Objects.requireNonNull(t.get("taskName")).toString();
                    if (!taskList.contains(tmp)) {
                        taskList.add(tmp);
                    }
                }
                final var adapter = new ArrayAdapter<>(
                        OwnTasksFragment.this.getContext(),
                        android.R.layout.simple_list_item_1,
                        taskList
                ){
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        TextView textView = view.findViewById(android.R.id.text1);
                        textView.setTextColor(getResources().getColor(R.color.white));
                        return view;
                    }
                };
                lista.setAdapter(adapter);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}