import { t } from '../../i18n';
import { useState } from 'react';
import { Pencil, Reply, SmilePlus, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { useDraft } from '../../drafts';
import { timeAgo } from '../../format';
import { Avatar } from '../Avatar';
import { Markdown } from '../Markdown';
import { MarkdownEditor } from '../MarkdownEditor';
import { REACTIONS, type Comment, type User } from '../../types';

interface Props {
  taskId: number;
  comment: Comment;
  me: User;
  canEdit: boolean;
  isOwner: boolean;
  members: User[];
  onChanged: (comment: Comment) => void;
  onDeleted: (id: number) => void;
  /** Present on top-level comments: posts a reply to this thread. */
  onReplied?: (reply: Comment) => void;
  replies?: Comment[];
  uploadImage?: (file: File) => Promise<string>;
}

/** One comment with its reactions and (for thread starters) replies. */
export function CommentItem({
  taskId, comment, me, canEdit, isOwner, members, onChanged, onDeleted, onReplied, replies = [], uploadImage,
}: Props) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(comment.body);
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState(false);
  const [replying, setReplying] = useState(false);
  const [reply, setReply, clearReply] = useDraft(onReplied ? `reply:${comment.id}` : null);
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

  const react = async (emoji: string) => {
    setPicker(false);
    try {
      onChanged(await api.react(taskId, comment.id, emoji));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const sendReply = async () => {
    if (!reply.trim() || !onReplied) return;
    setBusy(true);
    try {
      onReplied(await api.replyToComment(taskId, comment.id, reply.trim()));
      clearReply();
      setReplying(false);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className={`comment ${comment.parentId ? 'comment-reply' : ''}`} id={`comment-${comment.id}`}>
      <Avatar user={comment.author} size={comment.parentId ? 26 : 32} />
      <div className="comment-body">
        <div className="comment-head">
          <strong>{comment.author.displayName}</strong>
          <span className="muted small" title={new Date(comment.createdAt).toLocaleString()}>{timeAgo(comment.createdAt)}</span>
          {comment.internal && <span className="internal-badge" title={t('Only the team can see this')}>{t('Internal')}</span>}
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
                if (!window.confirm(replies.length ? 'Delete this comment and its replies?' : 'Delete this comment?')) return;
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
              label="Edit comment" onSubmitShortcut={save} onUploadImage={uploadImage} />
            <div className="button-row">
              <button className="btn btn-primary btn-sm" disabled={busy || !draft.trim()} onClick={save}>Save</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setEditing(false)}>Cancel</button>
            </div>
          </div>
        ) : (
          <>
            {comment.anchor && <blockquote className="comment-anchor">“{comment.anchor}”</blockquote>}
            <Markdown>{comment.body}</Markdown>
          </>
        )}

        <div className="comment-footer">
          {comment.reactions.map((r) => (
            <button key={r.emoji} className={`reaction ${r.mine ? 'mine' : ''}`} title={r.users.join(', ')}
              aria-label={`${r.emoji} ${r.count}, ${r.mine ? 'remove your reaction' : 'react'}`} aria-pressed={r.mine}
              onClick={() => react(r.emoji)}>
              <span aria-hidden>{r.emoji}</span> {r.count}
            </button>
          ))}
          <div className="menu">
            <button className="icon-button sm comment-action" aria-label="Add reaction" title="React"
              aria-expanded={picker} onClick={() => setPicker(!picker)}><SmilePlus size={15} /></button>
            {picker && (
              <div className="reaction-picker" role="menu">
                {REACTIONS.map((emoji) => (
                  <button key={emoji} role="menuitem" aria-label={`React with ${emoji}`} onClick={() => react(emoji)}>{emoji}</button>
                ))}
              </div>
            )}
          </div>
          {onReplied && canEdit && !replying && (
            <button className="link small reply-link" onClick={() => setReplying(true)}><Reply size={13} /> Reply</button>
          )}
        </div>

        {(replies.length > 0 || replying) && (
          <ul className="comments replies">
            {replies.map((r) => (
              <CommentItem key={r.id} taskId={taskId} comment={r} me={me} canEdit={canEdit} isOwner={isOwner}
                members={members} onChanged={onChanged} onDeleted={onDeleted} uploadImage={uploadImage} />
            ))}
            {replying && (
              <li className="reply-form">
                <MarkdownEditor value={reply} onChange={setReply} members={members} rows={2} maxLength={2000}
                  label="Reply" placeholder="Write a reply… Ctrl+Enter to send." onSubmitShortcut={sendReply}
                  onUploadImage={uploadImage} />
                <div className="button-row">
                  <button className="btn btn-primary btn-sm" disabled={busy || !reply.trim()} onClick={sendReply}>Reply</button>
                  <button className="btn btn-ghost btn-sm" onClick={() => setReplying(false)}>Cancel</button>
                </div>
              </li>
            )}
          </ul>
        )}
      </div>
    </li>
  );
}
