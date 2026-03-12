-- USERS 테이블의 email 컬럼에 uk 인덱스 생성

create unique index uk_email on USERS (email);