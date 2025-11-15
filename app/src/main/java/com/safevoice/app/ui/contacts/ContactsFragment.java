package com.safevoice.app.ui.contacts;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.safevoice.app.R;
import com.safevoice.app.databinding.FragmentContactsBinding;
import com.safevoice.app.models.Contact;
import com.safevoice.app.utils.ContactsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class ContactsFragment extends Fragment implements ContactsAdapter.OnContactOptionsClickListener, ConnectionRequestAdapter.OnRequestInteractionListener {

    private static final String TAG = "ContactsFragment";

    private FragmentContactsBinding binding;
    private ContactsManager contactsManager;

    private ContactsAdapter contactsAdapter;
    private ConnectionRequestAdapter requestAdapter;

    private List<Contact> priorityContactList;
    private List<DocumentSnapshot> incomingRequestList;

    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentContactsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        contactsManager = ContactsManager.getInstance(requireContext());

        priorityContactList = new ArrayList<>();
        incomingRequestList = new ArrayList<>();

        setupRecyclerViews();
        setupButtonClickListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            cleanupExpiredRequests();
            loadContacts();
            listenForConnectionRequests();
        } else {
            // Handle case where user is not logged in
        }
    }

    private void setupRecyclerViews() {
        binding.recyclerViewContacts.setLayoutManager(new LinearLayoutManager(getContext()));
        contactsAdapter = new ContactsAdapter(priorityContactList, this);
        binding.recyclerViewContacts.setAdapter(contactsAdapter);

        binding.recyclerViewRequests.setLayoutManager(new LinearLayoutManager(getContext()));
        requestAdapter = new ConnectionRequestAdapter(incomingRequestList, this);
        binding.recyclerViewRequests.setAdapter(requestAdapter);
    }

    private void setupButtonClickListeners() {
        binding.buttonSetPrimaryContact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddEditContactDialog(null, true);
            }
        });

        binding.buttonAddPriorityContact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchUserDialog();
            }
        });
    }

    private void loadContacts() {
        // Load and display the primary contact from SharedPreferences
        Contact primaryContact = contactsManager.getPrimaryContact();
        if (primaryContact != null) {
            binding.textPrimaryContactName.setText(primaryContact.getName());
            binding.textPrimaryContactPhone.setText(primaryContact.getPhoneNumber());
            binding.textNoPrimaryContact.setVisibility(View.GONE);
            binding.textPrimaryContactName.setVisibility(View.VISIBLE);
            binding.textPrimaryContactPhone.setVisibility(View.VISIBLE);
        } else {
            binding.textNoPrimaryContact.setVisibility(View.VISIBLE);
            binding.textPrimaryContactName.setVisibility(View.GONE);
            binding.textPrimaryContactPhone.setVisibility(View.GONE);
        }

        // Load priority contacts from SharedPreferences
        priorityContactList = contactsManager.getPriorityContacts();
        contactsAdapter.updateContacts(priorityContactList);
    }

    private void listenForConnectionRequests() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        db.collection("connection_requests")
                .whereEqualTo("recipientUid", currentUser.getUid())
                .addSnapshotListener(new com.google.firebase.firestore.EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot snapshots, @Nullable com.google.firebase.firestore.FirebaseFirestoreException e) {
                        if (e != null) {
                            Log.w(TAG, "Listen failed.", e);
                            return;
                        }
                        incomingRequestList.clear();
                        if (snapshots != null) {
                            incomingRequestList.addAll(snapshots.getDocuments());
                        }
                        requestAdapter.notifyDataSetChanged();
                        updateRequestUiVisibility();
                    }
                });
    }

    private void updateRequestUiVisibility() {
        if (incomingRequestList.isEmpty()) {
            binding.recyclerViewRequests.setVisibility(View.GONE);
            binding.textNoRequests.setVisibility(View.VISIBLE);
        } else {
            binding.recyclerViewRequests.setVisibility(View.VISIBLE);
            binding.textNoRequests.setVisibility(View.GONE);
        }
    }

    private void showSearchUserDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_search_user, null);
        final EditText emailEditText = dialogView.findViewById(R.id.edit_text_search_email);

        builder.setView(dialogView)
                .setTitle("Send Connection Request")
                .setPositiveButton("Search", null) // Set to null to override closing
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String email = emailEditText.getText().toString().trim();
                        if (TextUtils.isEmpty(email)) {
                            emailEditText.setError("Email cannot be empty.");
                            return;
                        }
                        searchUserByEmail(email, dialog);
                    }
                });
            }
        });
        dialog.show();
    }

    // --- THIS IS THE CORRECTED METHOD WITH THE ERROR POP-UP ---
    private void searchUserByEmail(String email, final AlertDialog searchDialog) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || email.equalsIgnoreCase(currentUser.getEmail())) {
            Toast.makeText(getContext(), R.string.cannot_add_self, Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").whereEqualTo("email", email).limit(1).get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful() && !task.getResult().isEmpty()) {
                            DocumentSnapshot userDoc = task.getResult().getDocuments().get(0);
                            String recipientUid = userDoc.getId();
                            String recipientName = userDoc.getString("verifiedName");
                            confirmAndSendRequest(recipientUid, recipientName, searchDialog);
                        } else {
                            // --- NEW LOGIC TO CATCH THE ERROR AND SHOW A POP-UP ---
                            Exception e = task.getException();
                            if (e instanceof FirebaseFirestoreException && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                                // This is the specific error for a missing index.
                                String errorMessage = e.getMessage();
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("Firebase Index Required")
                                        .setMessage(errorMessage) // This will contain the URL
                                        .setPositiveButton("OK", null)
                                        .show();
                            } else {
                                // For all other errors, including a legitimate "user not found"
                                Toast.makeText(getContext(), R.string.user_not_found, Toast.LENGTH_SHORT).show();
                                Log.w(TAG, "Search failed", e);
                            }
                        }
                    }
                });
    }

    private void confirmAndSendRequest(final String recipientUid, final String recipientName, final AlertDialog searchDialog) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Confirm User")
                .setMessage("Send a connection request to " + recipientName + "?")
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sendConnectionRequest(recipientUid);
                        searchDialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendConnectionRequest(String recipientUid) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        final String senderUid = currentUser.getUid();

        DocumentReference requestRef = db.collection("connection_requests").document();

        db.collection("users").document(senderUid).get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                if (task.isSuccessful() && task.getResult() != null) {
                    String senderName = task.getResult().getString("verifiedName");

                    Map<String, Object> request = new HashMap<>();
                    request.put("senderUid", senderUid);
                    request.put("senderName", senderName);
                    request.put("recipientUid", recipientUid);
                    request.put("timestamp", FieldValue.serverTimestamp());

                    requestRef.set(request).addOnCompleteListener(new OnCompleteListener<Void>() {
                        @Override
                        public void onComplete(@NonNull Task<Void> task) {
                            if (task.isSuccessful()) {
                                Toast.makeText(getContext(), R.string.request_sent_success, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(), "Failed to send request.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }
        });
    }

    private void cleanupExpiredRequests() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        long oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        CollectionReference requestsRef = db.collection("connection_requests");

        Query expiredQuery = requestsRef
                .whereEqualTo("recipientUid", currentUser.getUid())
                .whereLessThan("timestamp", new java.util.Date(oneHourAgo));

        expiredQuery.get().addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
            @Override
            public void onComplete(@NonNull Task<QuerySnapshot> task) {
                if (task.isSuccessful() && !task.getResult().isEmpty()) {
                    WriteBatch batch = db.batch();
                    for (QueryDocumentSnapshot doc : task.getResult()) {
                        batch.delete(doc.getReference());
                    }
                    batch.commit().addOnCompleteListener(new OnCompleteListener<Void>() {
                        @Override
                        public void onComplete(@NonNull Task<Void> task) {
                            if (task.isSuccessful()) {
                                Log.d(TAG, "Cleaned up expired connection requests.");
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onAcceptRequest(DocumentSnapshot request) {
        String senderUid = request.getString("senderUid");
        String senderName = request.getString("senderName");

        if (senderUid == null) return;

        db.collection("users").document(senderUid).get()
                .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                        if (task.isSuccessful() && task.getResult() != null) {
                            DocumentSnapshot userProfile = task.getResult();
                            String senderPhone = userProfile.getString("phoneNumber");

                            if (senderPhone == null || senderPhone.isEmpty()) {
                                senderPhone = "No number provided";
                            }

                            Contact newContact = new Contact(senderName, senderPhone, senderUid);
                            contactsManager.addPriorityContact(newContact);
                            loadContacts();

                            request.getReference().delete();
                        } else {
                            Log.w(TAG, "Failed to get user profile for sender: " + senderUid, task.getException());
                            Toast.makeText(getContext(), "Could not find user profile to add contact.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    @Override
    public void onDeclineRequest(DocumentSnapshot request) {
        request.getReference().delete();
    }

    @Override
    public void onContactOptionsClicked(final Contact contact) {
        int position = priorityContactList.indexOf(contact);
        RecyclerView.ViewHolder holder = binding.recyclerViewContacts.findViewHolderForAdapterPosition(position);
        if (holder == null) return;
        View anchorView = holder.itemView.findViewById(R.id.button_contact_options);

        PopupMenu popup = new PopupMenu(requireContext(), anchorView);
        popup.getMenuInflater().inflate(R.menu.contact_options_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.action_edit_contact) {
                    showAddEditContactDialog(contact, false);
                    return true;
                } else if (itemId == R.id.action_delete_contact) {
                    contactsManager.deletePriorityContact(contact);
                    loadContacts();
                    Toast.makeText(getContext(), "Contact deleted.", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }
        });

        popup.show();
    }

    private void showAddEditContactDialog(@Nullable final Contact existingContact, final boolean isPrimary) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_contact, null);

        final EditText nameEditText = dialogView.findViewById(R.id.edit_text_contact_name);
        final EditText phoneEditText = dialogView.findViewById(R.id.edit_text_contact_phone);

        String title = (existingContact == null) ? (isPrimary ? "Set Primary Contact" : "Add Contact") : "Edit Contact";
        builder.setView(dialogView).setTitle(title);

        if (existingContact != null) {
            nameEditText.setText(existingContact.getName());
            phoneEditText.setText(existingContact.getPhoneNumber());
        }

        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                String name = nameEditText.getText().toString().trim();
                String phone = phoneEditText.getText().toString().trim();

                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(phone)) {
                    Toast.makeText(getContext(), "Name and phone number cannot be empty.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Contact newContact = new Contact(name, phone);

                if (isPrimary) {
                    contactsManager.savePrimaryContact(newContact);
                } else {
                    if (existingContact != null) {
                        contactsManager.deletePriorityContact(existingContact);
                    }
                    contactsManager.addPriorityContact(newContact);
                }
                loadContacts();
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });

        builder.create().show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}


/**
 * Inner Adapter class for Connection Requests
 */
class ConnectionRequestAdapter extends RecyclerView.Adapter<ConnectionRequestAdapter.RequestViewHolder> {

    private final List<DocumentSnapshot> requestList;
    private final OnRequestInteractionListener listener;

    public interface OnRequestInteractionListener {
        void onAcceptRequest(DocumentSnapshot request);
        void onDeclineRequest(DocumentSnapshot request);
    }

    public ConnectionRequestAdapter(List<DocumentSnapshot> requestList, OnRequestInteractionListener listener) {
        this.requestList = requestList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RequestViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_connection_request, parent, false);
        return new RequestViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RequestViewHolder holder, int position) {
        DocumentSnapshot request = requestList.get(position);
        String requesterName = request.getString("senderName");
        holder.nameTextView.setText(requesterName);

        holder.acceptButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onAcceptRequest(request);
            }
        });

        holder.declineButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onDeclineRequest(request);
            }
        });
    }

    @Override
    public int getItemCount() {
        return requestList.size();
    }

    static class RequestViewHolder extends RecyclerView.ViewHolder {
        final TextView nameTextView;
        final Button acceptButton;
        final Button declineButton;

        RequestViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.text_requester_name);
            acceptButton = itemView.findViewById(R.id.button_accept_request);
            declineButton = itemView.findViewById(R.id.button_decline_request);
        }
    }
}