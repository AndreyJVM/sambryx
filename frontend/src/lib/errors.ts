import { ApiError } from "./api";

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const messages: Record<string, string> = {
      SSH_HOST_KEY_UNKNOWN: "Ключ SSH-сервера ещё не добавлен в доверенные. Попросите администратора сверить отпечаток и настроить known_hosts.",
      SSH_HOST_KEY_CHANGED: "Ключ SSH-сервера изменился. Подключение заблокировано: возможна подмена сервера. Уточните причину у администратора; не заменяйте доверенный ключ без проверки.",
      SSH_HOST_KEY_UNAVAILABLE: "Не удалось прочитать доверенные SSH-ключи. Администратору нужно проверить подключение и права файла known_hosts.",
    };
    const message = messages[error.details?.code || ""];
    if (message) return message + (error.details?.fingerprint ? ` Полученный отпечаток: ${error.details.fingerprint}` : "");
  }
  return error instanceof Error ? error.message : "Неизвестная ошибка";
}
