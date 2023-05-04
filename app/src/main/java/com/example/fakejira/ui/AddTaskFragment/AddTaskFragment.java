package com.example.fakejira.ui.AddTaskFragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.fakejira.databinding.FragmentAddtaskBinding;

public class AddTaskFragment extends Fragment {

    private FragmentAddtaskBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        AddTaskViewModel profileViewModel =
                new ViewModelProvider(this).get(AddTaskViewModel.class);
        binding = FragmentAddtaskBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        return root;
    }
}
