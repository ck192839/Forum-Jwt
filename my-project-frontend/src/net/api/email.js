import {get} from "@/net";

export const apiEmailRecordList = (page, size, success) =>
    get(`/api/admin/email/list?page=${page}&size=${size}`, success)
export const apiEmailFailedTotal = (success) =>
    get('/api/admin/email/failed-count', success)
export const apiEmailResend = (id, success, failure) =>
    get(`/api/admin/email/resend?id=${id}`, success, failure)
