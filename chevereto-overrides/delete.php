<?php

/*
 * 自定义删除 API（新增文件，未修改任何原有代码）
 *
 * 用法（Halo 插件联动删除用）：
 *   POST /delete   body: key=API_KEY&id=ENCODED_ID
 *   （也兼容 GET /delete?key=...&id=...，方便调试）
 *
 * 认证：复用 Chevereto V1 API Key（ApiKey::verify），仅允许删除 Key 所属用户的图片，
 *       管理员 Key 可删除任意图片。
 */

use Chevereto\Legacy\Classes\ApiKey;
use Chevereto\Legacy\Classes\Image;
use Chevereto\Legacy\Classes\User;
use Chevereto\Legacy\G\Handler;
use Throwable;
use function Chevereto\Legacy\decodeID;
use function Chevereto\Legacy\G\json_document_output;
use function Chevereto\Vars\request;

return function (Handler $handler) {
    try {
        $request = request();
        $key = strval($request['key'] ?? '');
        $idEncoded = strval($request['id'] ?? '');

        if ($key === '') {
            throw new Exception('No key provided', 100);
        }
        if ($idEncoded === '') {
            throw new Exception('No id provided', 100);
        }

        $verify = ApiKey::verify($key);
        if ($verify === []) {
            throw new Exception('Invalid API key', 100);
        }
        $user = User::getSingle($verify['user_id']);
        $isAdmin = boolval($user['is_admin'] ?? false);

        $id = decodeID($idEncoded);
        if ($id == 0) {
            throw new Exception('Invalid image id', 100);
        }

        $image = Image::getSingle($id, false, false);
        if ($image === [] || $image === false) {
            throw new Exception("Content doesn't exist", 100);
        }

        if (! $isAdmin && (int) ($image['image_user_id'] ?? 0) !== (int) $user['id']) {
            throw new Exception('Invalid content owner request', 114);
        }

        $affected = Image::delete($id);

        json_document_output([
            'status_code' => 200,
            'success' => [
                'message' => 'Image deleted',
                'code' => 200,
                'affected' => $affected,
            ],
        ]);
        exit;
    } catch (Throwable $e) {
        json_document_output([
            'status_code' => 400,
            'error' => [
                'message' => $e->getMessage(),
                'code' => $e->getCode(),
            ],
        ]);
        exit;
    }
};
