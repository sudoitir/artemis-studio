/** Hand a file to the browser. Same-document blob, nothing leaves the machine. */
export function download(filename: string, contents: Blob | string, type = 'application/octet-stream'): void {
  const blob = typeof contents === 'string' ? new Blob([contents], { type }) : contents;
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
