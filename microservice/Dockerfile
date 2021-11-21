# lambda base image
FROM amazon/aws-lambda-python:3.8

# Optional : pip upgrade
RUN /var/lang/bin/python3.8 -m pip install --upgrade pip

# Install git 
RUN apt-get install git -y

# Create a local repository to clone
RUN git init dos

# Move working directory
WORKDIR dos

# Set core.sparseCheckout to True to clone only some folders
RUN git config core.sparseCheckout true

# Set remote repository
RUN git remote add -f origin https://github.com/kmu-bigdata/dos.git

# Set the folder to be cloned
RUN echo /microservice >> .git/info/sparse-checkout
RUN echo /dos/model >> .git/info/sparse-checkout
RUN echo /dos/scaler >> .git/info/sparse-checkout

# Clone
RUN git pull origin main

# Install packages
RUN pip install -r microservice/requirements.txt

# Move lambda_function.py from the Git repository to /var/task/ inside the container
RUN cp microservice/lambda_function.py /var/task/

# RUN lambda_function.handler
CMD ["lambda_function.handler"]
