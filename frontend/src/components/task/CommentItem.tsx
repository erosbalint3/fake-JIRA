import { useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';
import { Avatar } from '../Avatar';
import { Markdown } from '../Markdown';
import { MarkdownEditor } from '../MarkdownEditor';
import type { Comment, User } from '../../types';

interface Props {
  taskId: number;
  comment: Comment;
  me: User;
  canEdit: boolean;
  isOwner: boolean;
  members: User[];
  onChanged: (comment: Comment) => void;
  onDeleted: (id: number) => void;
}

/** One comment: authors can edit theirs; authors and the project owner can delete. */
export function CommentItem({ taskId, comment, me, canEdit, isOwner, members, onChanged, onDeleted }: Props) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(comment.body);
  const [busy, setBusy] = useState(false);
  const mine = comment.author.id === me.id;

  const save = async () => {
    if (!draft.trim()) return;
    setBusy(true);
    try {
      onChanged(await api.editComment(taskId, comment.id, draft.trim()));
      setEditing(false);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="comment">
      <Avatar user={comment.author} size={32} />
      <div className="comment-body">
        <div className="comment-head">
          <strong>{comment.author.displayName}</strong>
          <span className="muted small" title={new Date(comment.createdAt).toLocaleString()}>{timeAgo(comment.createdAt)}</span>
          {comment.editedAt && (
            <span className="muted small" title={`Edited ${new Date(comment.editedAt).toLocaleString()}`}>· edited</span>
          )}
          <span className="spacer" />
          {!editing && mine && canEdit && (
            <button className="icon-button sm comment-action" aria-label="Edit comment" title="Edit"
              onClick={() => {
                setDraft(comment.body);
                setEditing(true);
              }}><Pencil size={14} /></button>
          )}
          {!editing && ((mine && canEdit) || isOwner) && (
            <button className="icon-button sm comment-action" aria-label="Delete comment" title="Delete"
              onClick={async () => {
                if (!window.confirm('Delete this comment?')) return;
                try {
                  await api.deleteComment(taskId, comment.id);
                  onDeleted(comment.id);
                } catch (e) {
                  toast((e as ApiError).message, 'error');
                }
              }}><Trash2 size={14} /></button>
          )}
        </div>
        {editing ? (
          <div className="comment-edit">
            <MarkdownEditor value={draft} onChange={setDraft} members={members} rows={3} maxLength={2000}
              label="Edit comment" onSubmitShortcut={save} />
            <div className="button-row">
              <button className="btn btn-primary btn-sm" disabled={busy || !draft.trim()} onClick={save}>Save</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setEditing(false)}>Cancel</button>
            </div>
          </div>
        ) : (
          <Markdown>{comment.body}</Markdown>
        )}
      </div>
    </li>
  );
}
