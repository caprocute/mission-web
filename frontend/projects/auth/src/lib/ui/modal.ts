/** Mở/đóng `<dialog>` dạng modal; jsdom và trình duyệt cũ không có showModal thì dùng thuộc tính open. */
export function openModal(d: HTMLDialogElement): void {
  if (d.open) return;
  if (typeof d.showModal === 'function') d.showModal();
  else d.setAttribute('open', '');
}

export function closeModal(d: HTMLDialogElement): void {
  if (typeof d.close === 'function') d.close();
  else d.removeAttribute('open');
}
