package com.example.fakejira.ui.Profile;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.example.fakejira.R;
import com.example.fakejira.databinding.FragmentProfileBinding;
import com.google.firebase.auth.FirebaseAuth;

public class profileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private TextView userName;
    private TextView userEmail;
    private FirebaseAuth mAuth = FirebaseAuth.getInstance();

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        ProfileViewModel profileViewModel =
                new ViewModelProvider(this).get(ProfileViewModel.class);
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        this.userName = root.findViewById(R.id.UsernameText);
        this.userEmail = root.findViewById(R.id.EmailText);
        final var user = mAuth.getCurrentUser();
        this.userName.setText(user.getEmail().split("@")[0]);
        this.userEmail.setText(user.getEmail());
        return root;
    }
}