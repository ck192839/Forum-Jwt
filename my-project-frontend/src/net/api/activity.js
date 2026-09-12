import {get, post} from "@/net";

export const apiActivityList = (success, failure, error) => failure
    ? get('/api/activity/list', success, failure, error)
    : get('/api/activity/list', success)

export const apiActivityGrab = (data, success, failure) =>
    post('/api/activity/grab', data, success, failure)

export const apiActivityMyOrders = (success, failure, error) => failure
    ? get('/api/activity/my-orders', success, failure, error)
    : get('/api/activity/my-orders', success)

export const apiAdminActivityList = (page, size, keyword, success, failure, error) => failure
    ? get(`/api/admin/activity/list?page=${page}&size=${size}${keyword ? `&keyword=${keyword}` : ''}`, success, failure, error)
    : get(`/api/admin/activity/list?page=${page}&size=${size}${keyword ? `&keyword=${keyword}` : ''}`, success)

export const apiAdminActivitySave = (data, success, failure) =>
    post('/api/admin/activity/save', data, success, failure)

export const apiAdminActivityDelete = (id, success, failure) =>
    get(`/api/admin/activity/delete?id=${id}`, success, failure)

export const apiAdminActivitySetStatus = (id, status, success, failure) =>
    post('/api/admin/activity/status', {id, status}, success, failure)
